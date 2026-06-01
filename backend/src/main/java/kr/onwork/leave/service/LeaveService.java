package kr.onwork.leave.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.onwork.approval.service.ApprovalRoutingService;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.domain.UserStatus;
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
import kr.onwork.leave.dto.LeaveGrantRequest;
import kr.onwork.leave.dto.LeaveGrantResponse;
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
    private final ApprovalRoutingService approvalRoutingService;

    public LeaveService(LeaveRequestRepository requestRepository,
                        LeaveBalanceRepository balanceRepository,
                        LeaveTypeRepository typeRepository,
                        LeaveApproverRepository approverRepository,
                        LeaveHistoryRepository historyRepository,
                        UserRepository userRepository,
                        NotificationService notificationService,
                        ApprovalRoutingService approvalRoutingService) {
        this.requestRepository = requestRepository;
        this.balanceRepository = balanceRepository;
        this.typeRepository = typeRepository;
        this.approverRepository = approverRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.approvalRoutingService = approvalRoutingService;
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

        // 결재 피로도 개선 #1: 단기 휴가 자동 승인 (반차 / 1일 이하 ANNUAL + 3일 이상 여유)
        if (isAutoApprovable(type, daysUsed, req.startDate())) {
            BigDecimal before = balance.remaining();
            balance.deduct(daysUsed);
            historyRepository.save(LeaveHistory.of(balance.getId(), saved.getId(), LeaveChangeType.USE,
                    daysUsed.negate(), before, balance.remaining(), principal.userId()));
            saved.approve(null, false);   // approverId=null → 시스템 자동 승인
            notificationService.notify(principal.userId(), NotificationService.LEAVE_AUTO_APPROVED,
                    "LEAVE", saved.getId(),
                    "휴가 신청이 자동 승인되었습니다 (단기 휴가 — 결재자 부담 완화)");
            return LeaveRequestResponse.of(saved, nameOf(principal.userId()));
        }

        // 결재자(대행 포함)에게 알림
        Long approverId = resolveActiveApprover(principal.userId());
        if (approverId != null) {
            notificationService.notify(approverId, NotificationService.LEAVE_REQUESTED,
                    "LEAVE", saved.getId(), "새 휴가 신청이 결재 대기 중입니다");
            approvalRoutingService.open(ApprovalRoutingService.TYPE_LEAVE, saved.getId(),
                    principal.userId(), approverId, departmentIdOf(principal.userId()));
        }
        return LeaveRequestResponse.of(saved, nameOf(principal.userId()));
    }

    /** 자동 승인 조건: 반차(0.5일) 또는 1일 이하 연차 + 시작일 3일 이상 여유(긴급/당일 제외). 보상휴가 제외. */
    private boolean isAutoApprovable(LeaveType type, BigDecimal daysUsed, LocalDate startDate) {
        if (type.isComp()) {
            return false;
        }
        if (type.isHalfDay()) {
            return true;
        }
        boolean shortAnnual = daysUsed.compareTo(BigDecimal.ONE) <= 0;
        boolean farEnough = !startDate.isBefore(LocalDate.now().plusDays(3));
        return shortAnnual && farEnough;
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
        // ADR-LVE-001: 신청 건마다 현재 유효 결재자를 실시간 산정해, 내가 결재자인 PENDING 건만 노출.
        // 팀장 부재 시 대행자(경영지원팀장), 팀장·대행자 모두 부재 시 경영진이 결재자가 된다.
        List<LeaveRequest> mine = requestRepository.findByStatusOrderByIdDesc(LeaveStatus.PENDING).stream()
                .filter(r -> !r.getUserId().equals(principal.userId()))
                .filter(r -> isActiveApproverFor(principal, r.getUserId()))
                .toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nameById = userRepository.findAllById(
                        mine.stream().map(LeaveRequest::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return mine.stream()
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
        if (!isActiveApproverFor(principal, request.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 휴가를 결재할 권한이 없습니다");
        }
        // 원래 팀장이 아닌 사람(대행자·경영진)이 처리하면 대행 결재로 표시
        boolean delegated = la == null || !principal.userId().equals(la.getApproverId());

        if (req.action() == LeaveProcessRequest.Action.ON_HOLD) {
            if (req.reason() == null || req.reason().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "보류 사유는 필수입니다");
            }
            request.hold(principal.userId(), req.reason());
            approvalRoutingService.complete(ApprovalRoutingService.TYPE_LEAVE, request.getId(),
                    principal.userId(), "ON_HOLD", req.reason());
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
        approvalRoutingService.complete(ApprovalRoutingService.TYPE_LEAVE, request.getId(),
                principal.userId(), "APPROVE", null);
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
            throw new BusinessException(ErrorCode.FORBIDDEN, "승인된 휴가는 승인자 취소 API로만 취소할 수 있습니다");
        }
        request.cancel();
        approvalRoutingService.cancel(ApprovalRoutingService.TYPE_LEAVE, request.getId(), "신청자 취소");
        return LeaveRequestResponse.of(request, nameOf(request.getUserId()));
    }

    @Transactional
    public LeaveRequestResponse cancelApproved(AuthPrincipal principal, Long id) {
        LeaveRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "승인자는 본인 신청을 취소할 수 없습니다");
        }
        if (request.getStatus() != LeaveStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "승인된 휴가만 승인자 취소할 수 있습니다");
        }
        requireLeaveApprover(principal, request.getUserId());

        LeaveBalance balance = balanceRepository.findById(request.getLeaveBalanceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_BALANCE_NOT_FOUND));
        BigDecimal before = balance.remaining();
        balance.restore(request.getDaysUsed());
        historyRepository.save(LeaveHistory.of(balance.getId(), request.getId(), LeaveChangeType.CANCEL,
                request.getDaysUsed(), before, balance.remaining(), principal.userId()));
        request.cancel();
        notificationService.notify(request.getUserId(), NotificationService.LEAVE_CANCELLED,
                "LEAVE", request.getId(), "승인된 휴가가 승인자에 의해 취소되었습니다");
        return LeaveRequestResponse.of(request, nameOf(request.getUserId()));
    }

    @Transactional
    public LeaveGrantResponse grantCompLeave(AuthPrincipal principal, LeaveGrantRequest req) {
        short year = (short) (req.year() != null ? req.year() : LocalDate.now().getYear());
        LeaveType comp = typeRepository.findByCode("COMP")
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "보상휴가 유형이 없습니다"));
        List<LeaveGrantResponse.Result> results = new ArrayList<>();
        int success = 0;
        int failure = 0;
        for (Long userId : req.userIds()) {
            try {
                userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                LeaveBalance balance = balanceRepository.findByUserIdAndLeaveTypeIdAndYear(userId, comp.getId(), year)
                        .orElseGet(() -> balanceRepository.save(
                                LeaveBalance.create(userId, comp.getId(), BigDecimal.ZERO, year)));
                BigDecimal before = balance.remaining();
                balance.grant(req.days());
                historyRepository.save(LeaveHistory.of(balance.getId(), null, LeaveChangeType.GRANT,
                        req.days(), before, balance.remaining(), principal.userId()));
                notificationService.notify(userId, NotificationService.LEAVE_GRANTED,
                        "LEAVE", null, "보상휴가 " + req.days() + "일이 부여되었습니다");
                results.add(new LeaveGrantResponse.Result(userId, true, null));
                success++;
            } catch (BusinessException e) {
                results.add(new LeaveGrantResponse.Result(userId, false,
                        e.getErrorCode().name() + ": " + e.getMessage()));
                failure++;
            } catch (RuntimeException e) {
                results.add(new LeaveGrantResponse.Result(userId, false, e.getMessage()));
                failure++;
            }
        }
        return new LeaveGrantResponse(req.userIds().size(), success, failure, results);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(AuthPrincipal principal) {
        List<LeaveRequestResponse> mine = myRequests(principal);
        List<LeaveRequestResponse> pending = inbox(principal);
        long approved = mine.stream().filter(r -> r.status().equals(LeaveStatus.APPROVED.name())).count();
        long onHold = mine.stream().filter(r -> r.status().equals(LeaveStatus.ON_HOLD.name())).count();
        return Map.of(
                "my_total", mine.size(),
                "my_approved", approved,
                "my_on_hold", onHold,
                "pending_approval", pending.size()
        );
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

    /**
     * ADR-LVE-001: 현재 유효 결재자를 실시간 산정.
     * 팀장 → (팀장 부재) 대행자(경영지원팀장) → (대행자도 부재) 경영진(CEO/VP).
     * 부재 여부는 오늘자 승인된 휴가 유무로 자동 판단(is_absent 플래그 미사용).
     */
    private Long resolveActiveApprover(Long requesterId) {
        LeaveApprover la = approverOf(requesterId);
        if (la != null) {
            if (!isOnLeaveToday(la.getApproverId())) {
                return la.getApproverId();
            }
            if (la.getDelegateId() != null && !isOnLeaveToday(la.getDelegateId())) {
                return la.getDelegateId();
            }
        }
        return anExecutiveId();   // 팀장·대행자 모두 부재(또는 부서 미지정) → 경영진 배정
    }

    /** principal이 해당 신청의 현재 유효 결재자인지(자동 부재 반영). */
    private boolean isActiveApproverFor(AuthPrincipal principal, Long requesterId) {
        LeaveApprover la = approverOf(requesterId);
        if (la != null) {
            if (!isOnLeaveToday(la.getApproverId())) {
                return principal.userId().equals(la.getApproverId());
            }
            if (la.getDelegateId() != null && !isOnLeaveToday(la.getDelegateId())) {
                return principal.userId().equals(la.getDelegateId());
            }
        }
        // 팀장·대행자 모두 부재(또는 부서 없음) → 경영진이 유효 결재자
        return principal.role() == Role.CEO || principal.role() == Role.VP;
    }

    /** 오늘 승인된 휴가로 부재 중인가. */
    private boolean isOnLeaveToday(Long userId) {
        return userId != null && requestRepository.countActiveLeaveOn(userId, LocalDate.now()) > 0;
    }

    /** 에스컬레이션 대상 경영진 1인(VP 우선, 없으면 CEO). */
    private Long anExecutiveId() {
        List<User> execs = userRepository.findByRoleInAndStatus(List.of(Role.VP, Role.CEO), UserStatus.ACTIVE);
        return execs.stream().filter(u -> u.getRole() == Role.VP).findFirst()
                .or(() -> execs.stream().findFirst())
                .map(User::getId).orElse(null);
    }

    private void requireLeaveApprover(AuthPrincipal principal, Long requesterId) {
        if (isActiveApproverFor(principal, requesterId)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "이 휴가를 취소할 권한이 없습니다");
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getName).orElse("?");
    }

    private Long departmentIdOf(Long userId) {
        return userRepository.findById(userId)
                .map(User::getDepartment)
                .map(department -> department.getId())
                .orElse(null);
    }
}
