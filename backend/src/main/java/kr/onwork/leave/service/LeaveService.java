package kr.onwork.leave.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.error.BusinessException;
import kr.onwork.common.error.ErrorCode;
import kr.onwork.common.repository.UserRepository;
import kr.onwork.common.security.AuthPrincipal;
import kr.onwork.leave.domain.LeaveApprover;
import kr.onwork.leave.domain.LeaveBalance;
import kr.onwork.leave.domain.LeaveChangeType;
import kr.onwork.leave.domain.LeaveHistory;
import kr.onwork.leave.domain.LeaveRequest;
import kr.onwork.leave.domain.LeaveStatus;
import kr.onwork.leave.domain.LeaveType;
import kr.onwork.leave.dto.LeaveBalanceResponse;
import kr.onwork.leave.dto.LeaveProcessRequest;
import kr.onwork.leave.dto.LeaveRequestCreate;
import kr.onwork.leave.dto.LeaveRequestResponse;
import kr.onwork.leave.repository.LeaveApproverRepository;
import kr.onwork.leave.repository.LeaveBalanceRepository;
import kr.onwork.leave.repository.LeaveHistoryRepository;
import kr.onwork.leave.repository.LeaveRequestRepository;
import kr.onwork.leave.repository.LeaveTypeRepository;
import kr.onwork.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 휴가 비즈니스 로직. 신청 시 차감 보류, 승인 시점에만 잔여 차감(승인 전 미반영),
 * 반려/취소 시 롤백(leave_histories 감사). 결재자 부재 시 대행자 처리(ADR-003).
 */
@Service
public class LeaveService {

    private final LeaveRequestRepository requestRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveTypeRepository typeRepository;
    private final LeaveApproverRepository approverRepository;
    private final LeaveHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LeaveService(LeaveRequestRepository requestRepository,
                        LeaveBalanceRepository balanceRepository,
                        LeaveTypeRepository typeRepository,
                        LeaveApproverRepository approverRepository,
                        LeaveHistoryRepository historyRepository,
                        UserRepository userRepository,
                        NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.balanceRepository = balanceRepository;
        this.typeRepository = typeRepository;
        this.approverRepository = approverRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------- 잔여 조회
    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> myBalances(AuthPrincipal principal, int year) {
        Map<Long, LeaveType> typeById = typeRepository.findAll().stream()
                .collect(Collectors.toMap(LeaveType::getId, t -> t));
        return balanceRepository.findByUserIdAndYear(principal.userId(), (short) year).stream()
                .map(b -> {
                    LeaveType t = typeById.get(b.getLeaveTypeId());
                    return new LeaveBalanceResponse(b.getId(), b.getLeaveTypeId(),
                            t != null ? t.getCode() : "?", t != null ? t.getName() : "?",
                            b.getTotalDays(), b.getUsedDays(), b.remaining(), b.getYear());
                })
                .toList();
    }

    // ---------------------------------------------------------------- 신청
    @Transactional
    public LeaveRequestResponse request(AuthPrincipal principal, LeaveRequestCreate req) {
        LeaveType type = typeRepository.findById(req.leaveTypeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PAYLOAD, "휴가 종류가 올바르지 않습니다"));
        if (req.endDate().isBefore(req.startDate())) {
            throw new BusinessException(ErrorCode.LEAVE_INVALID_PERIOD);
        }
        BigDecimal daysUsed = computeDays(type, req.startDate(), req.endDate());

        short year = (short) req.startDate().getYear();
        LeaveBalance balance = resolveBalance(principal.userId(), type, year);
        if (balance.remaining().compareTo(daysUsed) < 0) {
            throw new BusinessException(ErrorCode.LEAVE_INSUFFICIENT_BALANCE);
        }
        if (requestRepository.countOverlapping(principal.userId(), req.startDate(), req.endDate()) > 0) {
            throw new BusinessException(ErrorCode.LEAVE_OVERLAP);
        }

        LeaveRequest saved = requestRepository.save(LeaveRequest.create(
                principal.userId(), balance.getId(), req.startDate(), req.endDate(), daysUsed, req.reason()));

        // 결재자(대행 포함)에게 알림
        Long approverId = resolveActiveApprover(principal.userId());
        if (approverId != null) {
            notificationService.notify(approverId, NotificationService.LEAVE_REQUESTED,
                    "LEAVE", saved.getId(), "새 휴가 신청이 결재 대기 중입니다");
        }
        return LeaveRequestResponse.of(saved, nameOf(principal.userId()));
    }

    // ---------------------------------------------------------------- 결재함 / 내 신청
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> myRequests(AuthPrincipal principal) {
        String name = nameOf(principal.userId());
        return requestRepository.findByUserIdOrderByIdDesc(principal.userId()).stream()
                .map(r -> LeaveRequestResponse.of(r, name)).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> inbox(AuthPrincipal principal) {
        List<Long> targetUserIds;
        if (principal.role() == Role.CEO || principal.role() == Role.VP) {
            targetUserIds = userRepository.search(null, null, null).stream().map(User::getId).toList();
        } else {
            List<Long> deptIds = approverRepository
                    .findByApproverIdOrDelegateId(principal.userId(), principal.userId()).stream()
                    .filter(la -> la.activeApproverId().equals(principal.userId()))
                    .map(LeaveApprover::getDepartmentId).toList();
            if (deptIds.isEmpty()) {
                return List.of();
            }
            targetUserIds = deptIds.stream()
                    .flatMap(d -> userRepository.search(d, null, null).stream())
                    .map(User::getId).toList();
        }
        if (targetUserIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nameById = userRepository.findAllById(targetUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return requestRepository.findByUserIdInAndStatusOrderByIdDesc(targetUserIds, LeaveStatus.PENDING).stream()
                .map(r -> LeaveRequestResponse.of(r, nameById.getOrDefault(r.getUserId(), "?")))
                .toList();
    }

    // ---------------------------------------------------------------- 승인 / 보류
    @Transactional
    public LeaveRequestResponse process(AuthPrincipal principal, Long id, LeaveProcessRequest req) {
        LeaveRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.CANNOT_SELF_APPROVE);
        }
        LeaveApprover la = approverOf(request.getUserId());
        boolean exec = principal.role() == Role.CEO || principal.role() == Role.VP;
        boolean isActiveApprover = la != null && la.activeApproverId().equals(principal.userId());
        if (!exec && !isActiveApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 휴가를 결재할 권한이 없습니다");
        }
        boolean delegated = la != null && !principal.userId().equals(la.getApproverId());

        if (req.action() == LeaveProcessRequest.Action.ON_HOLD) {
            if (req.reason() == null || req.reason().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "보류 사유는 필수입니다");
            }
            request.hold(principal.userId(), req.reason());
            notificationService.notify(request.getUserId(), NotificationService.LEAVE_ON_HOLD,
                    "LEAVE", request.getId(), "휴가 신청이 보류되었습니다: " + req.reason());
            return LeaveRequestResponse.of(request, nameOf(request.getUserId()));
        }

        // APPROVE — 이 시점에만 잔여 차감
        LeaveBalance balance = balanceRepository.findById(request.getLeaveBalanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_BALANCE_NOT_FOUND));
        if (balance.remaining().compareTo(request.getDaysUsed()) < 0) {
            throw new BusinessException(ErrorCode.LEAVE_INSUFFICIENT_BALANCE);
        }
        BigDecimal before = balance.remaining();
        balance.deduct(request.getDaysUsed());
        historyRepository.save(LeaveHistory.of(balance.getId(), request.getId(), LeaveChangeType.USE,
                request.getDaysUsed().negate(), before, balance.remaining(), principal.userId()));
        request.approve(principal.userId(), delegated);
        notificationService.notify(request.getUserId(), NotificationService.LEAVE_APPROVED,
                "LEAVE", request.getId(), "휴가 신청이 승인되었습니다" + (delegated ? " (대행 결재)" : ""));
        return LeaveRequestResponse.of(request, nameOf(request.getUserId()));
    }

    // ---------------------------------------------------------------- 취소 (롤백)
    @Transactional
    public LeaveRequestResponse cancel(AuthPrincipal principal, Long id) {
        LeaveRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 신청만 취소할 수 있습니다");
        }
        if (request.getStatus() == LeaveStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 취소된 신청입니다");
        }
        if (request.getStatus() == LeaveStatus.APPROVED) {
            LeaveBalance balance = balanceRepository.findById(request.getLeaveBalanceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_BALANCE_NOT_FOUND));
            BigDecimal before = balance.remaining();
            balance.restore(request.getDaysUsed());
            historyRepository.save(LeaveHistory.of(balance.getId(), request.getId(), LeaveChangeType.CANCEL,
                    request.getDaysUsed(), before, balance.remaining(), principal.userId()));
        }
        request.cancel();
        return LeaveRequestResponse.of(request, nameOf(request.getUserId()));
    }

    // ---------------------------------------------------------------- helpers
    private BigDecimal computeDays(LeaveType type, LocalDate start, LocalDate end) {
        if (type.isHalfDay()) {
            if (!start.equals(end)) {
                throw new BusinessException(ErrorCode.LEAVE_INVALID_PERIOD, "반차는 하루만 신청할 수 있습니다");
            }
            return new BigDecimal("0.5");
        }
        long days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        if (days == 0) {
            throw new BusinessException(ErrorCode.LEAVE_INVALID_PERIOD, "근무일이 포함되어야 합니다");
        }
        return BigDecimal.valueOf(days);
    }

    /** 반차/연차는 ANNUAL 잔여에서, 보상휴가는 COMP 잔여에서 차감. */
    private LeaveBalance resolveBalance(Long userId, LeaveType type, short year) {
        String code = type.isComp() ? "COMP" : "ANNUAL";
        LeaveType balanceType = typeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        return balanceRepository.findByUserIdAndLeaveTypeIdAndYear(userId, balanceType.getId(), year)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_BALANCE_NOT_FOUND));
    }

    private LeaveApprover approverOf(Long requesterId) {
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (requester.getDepartment() == null) {
            return null;
        }
        return approverRepository.findByDepartmentId(requester.getDepartment().getId()).orElse(null);
    }

    private Long resolveActiveApprover(Long requesterId) {
        LeaveApprover la = approverOf(requesterId);
        return la != null ? la.activeApproverId() : null;
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getName).orElse("?");
    }
}
