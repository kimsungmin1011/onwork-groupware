package kr.onwork.attendance.service;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.onwork.approval.service.ApprovalRoutingService;
import kr.onwork.attendance.domain.AnomalyType;
import kr.onwork.attendance.domain.AttendanceSetting;
import kr.onwork.attendance.domain.DailyWorkRecord;
import kr.onwork.attendance.domain.MonthlySummary;
import kr.onwork.attendance.domain.OvertimeRequest;
import kr.onwork.attendance.domain.OvertimeStatus;
import kr.onwork.attendance.domain.WorkAnomaly;
import kr.onwork.attendance.dto.AnomalyConfirmRequest;
import kr.onwork.attendance.dto.AnomalyResponse;
import kr.onwork.attendance.dto.AttendanceProcessRequest;
import kr.onwork.attendance.dto.ClockResponse;
import kr.onwork.attendance.dto.MonthlySummaryRequest;
import kr.onwork.attendance.dto.OvertimeCreateRequest;
import kr.onwork.attendance.dto.OvertimeResponse;
import kr.onwork.attendance.repository.AttendanceSettingRepository;
import kr.onwork.attendance.repository.DailyWorkRecordRepository;
import kr.onwork.attendance.repository.MonthlySummaryRepository;
import kr.onwork.attendance.repository.OvertimeRequestRepository;
import kr.onwork.attendance.repository.WorkAnomalyRepository;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.domain.UserStatus;
import kr.onwork.common.dto.ApproverView;
import kr.onwork.common.error.BusinessException;
import kr.onwork.common.error.ErrorCode;
import kr.onwork.common.repository.UserRepository;
import kr.onwork.common.security.AuthPrincipal;
import kr.onwork.common.service.ApproverViewFactory;
import kr.onwork.leave.repository.LeaveRequestRepository;
import kr.onwork.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근태 비즈니스 로직. 출퇴근은 서버 시각 기준(UC-ATTENDANCE-01), 당일 1회.
 * 결근 자동 감지는 자정 배치(ADR-ATT-001, AttendanceScheduler가 호출).
 */
@Service
public class AttendanceService {

    private final DailyWorkRecordRepository recordRepository;
    private final WorkAnomalyRepository anomalyRepository;
    private final OvertimeRequestRepository overtimeRepository;
    private final AttendanceSettingRepository settingRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final MonthlySummaryRepository monthlySummaryRepository;
    private final NotificationService notificationService;
    private final ApprovalRoutingService approvalRoutingService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ApproverViewFactory approverViewFactory;

    public AttendanceService(DailyWorkRecordRepository recordRepository,
                             WorkAnomalyRepository anomalyRepository,
                             OvertimeRequestRepository overtimeRepository,
                             AttendanceSettingRepository settingRepository,
                             UserRepository userRepository,
                             Clock clock,
                             MonthlySummaryRepository monthlySummaryRepository,
                             NotificationService notificationService,
                             ApprovalRoutingService approvalRoutingService,
                             LeaveRequestRepository leaveRequestRepository,
                             ApproverViewFactory approverViewFactory) {
        this.recordRepository = recordRepository;
        this.anomalyRepository = anomalyRepository;
        this.overtimeRepository = overtimeRepository;
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.monthlySummaryRepository = monthlySummaryRepository;
        this.notificationService = notificationService;
        this.approvalRoutingService = approvalRoutingService;
        this.leaveRequestRepository = leaveRequestRepository;
        this.approverViewFactory = approverViewFactory;
    }

    // ---------------------------------------------------------------- 출근
    @Transactional
    public ClockResponse clockIn(AuthPrincipal principal) {
        User user = loadUser(principal.userId());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        DailyWorkRecord record = recordRepository.findByUserIdAndDate(user.getId(), today)
                .orElseGet(() -> DailyWorkRecord.of(user.getId(), today));
        if (record.hasClockIn()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 출근 처리되었습니다");
        }
        LocalTime start = user.getWorkGroup().getWorkStartTime();
        LocalTime lateLine = start.plusMinutes(setting().getGraceInMinutes());
        boolean late = now.toLocalTime().isAfter(lateLine);
        record.clockIn(now, late);
        DailyWorkRecord saved = recordRepository.save(record);
        if (late) {
            anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.LATE));
        }
        return ClockResponse.from(saved, late, false);
    }

    // ---------------------------------------------------------------- 퇴근
    @Transactional
    public ClockResponse clockOut(AuthPrincipal principal) {
        User user = loadUser(principal.userId());
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        DailyWorkRecord record = recordRepository.findByUserIdAndDate(user.getId(), today)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "출근 기록이 없습니다"));
        if (!record.hasClockIn()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "출근 기록이 없습니다");
        }
        if (record.hasClockOut()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 퇴근 처리되었습니다");
        }
        LocalTime end = user.getWorkGroup().getWorkEndTime();
        LocalTime earlyLine = end.minusMinutes(setting().getGraceOutMinutes());
        boolean early = now.toLocalTime().isBefore(earlyLine);
        int overtime = now.toLocalTime().isAfter(end)
                ? (int) Duration.between(end, now.toLocalTime()).toMinutes() : 0;
        record.clockOut(now, overtime, early);
        DailyWorkRecord saved = recordRepository.save(record);
        if (early) {
            anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.EARLY_LEAVE));
        }
        if (overtime > 0) {
            saved.markAnomaly();
            anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.UNAPPROVED_OVERTIME));
        }
        return ClockResponse.from(saved, false, early);
    }

    @Transactional(readOnly = true)
    public ClockResponse today(AuthPrincipal principal) {
        LocalDate today = LocalDate.now(clock);
        return recordRepository.findByUserIdAndDate(principal.userId(), today)
                .map(r -> ClockResponse.from(r, false, false))
                .orElse(new ClockResponse(null, today, null, null, 0, "NONE", false, false));
    }

    // ---------------------------------------------------------------- 결근 배치 (ADR-ATT-001)
    /** 지정일에 출근 기록이 없는 재직자에게 ABSENT, 퇴근 누락자에게 CLOCK_MISSING 이상 자동 생성. */
    @Transactional
    public int detectAbsences(LocalDate date) {
        List<User> active = userRepository.search(null, UserStatus.ACTIVE, null);
        int created = 0;
        for (User u : active) {
            DailyWorkRecord record = recordRepository.findByUserIdAndDate(u.getId(), date).orElse(null);
            if (record == null) {
                DailyWorkRecord absent = DailyWorkRecord.of(u.getId(), date);
                absent.markAnomaly();
                DailyWorkRecord saved = recordRepository.save(absent);
                anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.ABSENT));
                created++;
            } else if (record.hasClockIn() && !record.hasClockOut()
                    && !anomalyRepository.existsByDailyWorkRecordIdAndAnomalyType(
                            record.getId(), AnomalyType.CLOCK_MISSING)) {
                record.markAnomaly();
                recordRepository.save(record);
                anomalyRepository.save(WorkAnomaly.of(record.getId(), AnomalyType.CLOCK_MISSING));
                created++;
            }
        }
        return created;
    }

    // ---------------------------------------------------------------- 팀 근태 이상 (UC-ATTENDANCE-02)
    @Transactional(readOnly = true)
    public List<AnomalyResponse> listAnomalies(AuthPrincipal principal, LocalDate date) {
        List<User> scope = scopedUsers(principal);
        Map<Long, String> nameById = scope.stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        List<DailyWorkRecord> records = recordRepository.findByUserIdInAndDate(
                scope.stream().map(User::getId).toList(), date);
        Map<Long, DailyWorkRecord> recordById = records.stream()
                .collect(Collectors.toMap(DailyWorkRecord::getId, Function.identity()));
        if (records.isEmpty()) {
            return List.of();
        }
        return anomalyRepository.findByDailyWorkRecordIdIn(records.stream().map(DailyWorkRecord::getId).toList())
                .stream()
                .map(a -> {
                    DailyWorkRecord rec = recordById.get(a.getDailyWorkRecordId());
                    return new AnomalyResponse(a.getId(), rec.getUserId(),
                            nameById.getOrDefault(rec.getUserId(), "?"),
                            rec.getDate(), a.getAnomalyType().name(), a.isConfirmed());
                })
                .toList();
    }

    @Transactional
    public AnomalyResponse confirmAnomaly(AuthPrincipal principal, Long anomalyId, AnomalyConfirmRequest req) {
        WorkAnomaly anomaly = anomalyRepository.findById(anomalyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (anomaly.isConfirmed()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 확인된 이상 항목입니다");
        }
        DailyWorkRecord record = recordRepository.findById(anomaly.getDailyWorkRecordId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireManageable(principal, record.getUserId());
        if (req != null && req.anomalyType() != null) {
            anomaly.reclassify(req.anomalyType());
        }
        if (anomaly.getAnomalyType() == AnomalyType.UNAPPROVED_OVERTIME
                && req != null
                && Boolean.FALSE.equals(req.overtimeApproved())) {
            record.setOvertimeMinutes(0);
        }
        anomaly.confirm(principal.userId());
        User target = loadUser(record.getUserId());
        return new AnomalyResponse(anomaly.getId(), record.getUserId(), target.getName(),
                record.getDate(), anomaly.getAnomalyType().name(), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> monthlySummary(AuthPrincipal principal, YearMonth yearMonth) {
        List<User> scope = scopedUsers(principal);
        List<Long> userIds = scope.stream().map(User::getId).toList();
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();
        List<DailyWorkRecord> records = userIds.isEmpty()
                ? List.of()
                : recordRepository.findByUserIdInAndDateBetween(userIds, start, end);
        long anomalyCount = records.stream()
                .filter(r -> r.getStatus().name().equals("ANOMALY"))
                .count();
        long clockMissingCount = records.stream()
                .filter(r -> r.hasClockIn() && !r.hasClockOut())
                .count();
        int overtimeMinutes = records.stream()
                .mapToInt(DailyWorkRecord::getOvertimeMinutes)
                .sum();
        return Map.of(
                "year_month", yearMonth.toString(),
                "total_employees", scope.size(),
                "record_count", records.size(),
                "anomaly_count", anomalyCount,
                "clock_missing_count", clockMissingCount,
                "overtime_minutes", overtimeMinutes
        );
    }

    @Transactional
    public Map<String, Object> closeMonthlySummary(AuthPrincipal principal, MonthlySummaryRequest req) {
        YearMonth yearMonth = YearMonth.of(req.year(), req.month());
        List<User> scope = scopedUsers(principal);
        List<Long> userIds = scope.stream().map(User::getId).toList();
        String key = yearMonth.toString();
        if (monthlySummaryRepository.existsByUserIdInAndYearMonth(userIds, key)) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 마감된 월입니다");
        }
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();
        List<DailyWorkRecord> records = userIds.isEmpty()
                ? List.of()
                : recordRepository.findByUserIdInAndDateBetween(userIds, start, end);
        Map<Long, List<DailyWorkRecord>> byUser = records.stream()
                .collect(Collectors.groupingBy(DailyWorkRecord::getUserId));
        List<WorkAnomaly> anomalies = records.isEmpty()
                ? List.of()
                : anomalyRepository.findByDailyWorkRecordIdIn(records.stream().map(DailyWorkRecord::getId).toList());
        long unconfirmed = anomalies.stream().filter(a -> !a.isConfirmed()).count();
        if (unconfirmed > 0 && !req.force()) {
            return Map.of(
                    "year_month", key,
                    "closed", false,
                    "requires_confirmation", true,
                    "unconfirmed_anomaly_count", unconfirmed,
                    "message", "미확정 근태 이상이 있어 확인 후 마감해야 합니다"
            );
        }
        Map<Long, List<WorkAnomaly>> anomaliesByRecord = anomalies.stream()
                .collect(Collectors.groupingBy(WorkAnomaly::getDailyWorkRecordId));
        int closed = 0;
        int totalOvertime = 0;
        for (User u : scope) {
            List<DailyWorkRecord> userRecords = byUser.getOrDefault(u.getId(), List.of());
            int late = 0;
            int early = 0;
            int absent = 0;
            int overtime = 0;
            for (DailyWorkRecord r : userRecords) {
                overtime += r.getOvertimeMinutes();
                for (WorkAnomaly a : anomaliesByRecord.getOrDefault(r.getId(), List.of())) {
                    if (a.getAnomalyType() == AnomalyType.LATE) late++;
                    if (a.getAnomalyType() == AnomalyType.EARLY_LEAVE) early++;
                    if (a.getAnomalyType() == AnomalyType.ABSENT) absent++;
                }
            }
            totalOvertime += overtime;
            monthlySummaryRepository.save(MonthlySummary.close(
                    u.getId(), key, late, early, absent, overtime, principal.userId()));
            closed++;
        }
        return Map.of(
                "year_month", key,
                "closed", true,
                "closed_count", closed,
                "unconfirmed_anomaly_count", unconfirmed,
                "total_overtime_minutes", totalOvertime
        );
    }

    // ---------------------------------------------------------------- 시간외근로 (UC-03/04)
    @Transactional
    public OvertimeResponse requestOvertime(AuthPrincipal principal, OvertimeCreateRequest req) {
        if (!req.expectedEndAt().isAfter(req.expectedStartAt())) {
            throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "종료 시각은 시작 시각 이후여야 합니다");
        }
        OvertimeRequest saved = overtimeRepository.save(OvertimeRequest.create(
                principal.userId(), req.requestDate(), req.expectedStartAt(), req.expectedEndAt(), req.reason()));
        scopedUsersForManagerNotification(principal.userId()).forEach(managerId ->
                notificationService.notify(managerId, NotificationService.OVERTIME_REQUESTED,
                        "ATTENDANCE", saved.getId(), "시간외 근로 신청이 결재 대기 중입니다"));
        Long approverId = managerIdOf(principal.userId());
        approvalRoutingService.open(ApprovalRoutingService.TYPE_OVERTIME, saved.getId(),
                principal.userId(), approverId, departmentIdOf(principal.userId()));
        return OvertimeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<OvertimeResponse> myOvertime(AuthPrincipal principal) {
        return overtimeRepository.findByUserIdOrderByIdDesc(principal.userId())
                .stream().map(r -> OvertimeResponse.from(r, null, buildOvertimeApprover(r))).toList();
    }

    @Transactional(readOnly = true)
    public List<OvertimeResponse> overtimeInbox(AuthPrincipal principal) {
        Map<Long, String> nameById = scopedUsers(principal).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return overtimeRepository.findByUserIdInAndStatusOrderByIdDesc(nameById.keySet().stream().toList(), OvertimeStatus.PENDING)
                .stream().map(r -> OvertimeResponse.from(r, nameById.get(r.getUserId()), buildOvertimeApprover(r)))
                .toList();
    }

    /**
     * 시간외 신청의 결재자 표현. PENDING → 팀장(부재 시 경영진 대행),
     * APPROVED/REJECTED → 실제 처리자. 팀장 부재는 오늘자 승인 휴가로 판단.
     */
    private ApproverView buildOvertimeApprover(OvertimeRequest r) {
        OvertimeStatus st = r.getStatus();
        if (st == OvertimeStatus.APPROVED || st == OvertimeStatus.REJECTED) {
            Long approverId = r.getApproverId();
            if (approverId == null) {
                return null;
            }
            Long manager = managerIdOf(r.getUserId());
            boolean delegated = manager == null || !approverId.equals(manager);
            Long absentId = (delegated && manager != null) ? manager : null;
            return approverViewFactory.of(approverId, delegated, absentId);
        }
        // PENDING — 팀장이 결재. 팀장 부재 시 경영진이 대행.
        Long manager = managerIdOf(r.getUserId());
        if (manager != null && !isOnLeaveToday(manager)) {
            return approverViewFactory.of(manager, false, null);
        }
        return approverViewFactory.of(anExecutiveId(), true, manager);
    }

    /** 오늘 승인된 휴가로 부재 중인가(시간외 대행 판단용). */
    private boolean isOnLeaveToday(Long userId) {
        return userId != null
                && leaveRequestRepository.countActiveLeaveOn(userId, LocalDate.now(clock)) > 0;
    }

    /** 대행 대상 경영진 1인(VP 우선, 없으면 CEO). */
    private Long anExecutiveId() {
        List<User> execs = userRepository.findByRoleInAndStatus(List.of(Role.VP, Role.CEO), UserStatus.ACTIVE);
        return execs.stream().filter(u -> u.getRole() == Role.VP).findFirst()
                .or(() -> execs.stream().findFirst())
                .map(User::getId).orElse(null);
    }

    @Transactional
    public OvertimeResponse processOvertime(AuthPrincipal principal, Long id, AttendanceProcessRequest req) {
        OvertimeRequest request = overtimeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.CANNOT_SELF_APPROVE);
        }
        requireManageable(principal, request.getUserId());
        if (req.action() == AttendanceProcessRequest.Action.REJECT) {
            if (req.reason() == null || req.reason().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "반려 사유는 필수입니다");
            }
            request.reject(principal.userId(), req.reason());
            approvalRoutingService.complete(ApprovalRoutingService.TYPE_OVERTIME, request.getId(),
                    principal.userId(), "REJECT", req.reason());
            notificationService.notify(request.getUserId(), NotificationService.OVERTIME_REJECTED,
                    "ATTENDANCE", request.getId(), "시간외 근로 신청이 반려되었습니다: " + req.reason());
        } else {
            request.approve(principal.userId());
            approvalRoutingService.complete(ApprovalRoutingService.TYPE_OVERTIME, request.getId(),
                    principal.userId(), "APPROVE", null);
            notificationService.notify(request.getUserId(), NotificationService.OVERTIME_APPROVED,
                    "ATTENDANCE", request.getId(), "시간외 근로 신청이 승인되었습니다");
        }
        return OvertimeResponse.from(request);
    }

    // ---------------------------------------------------------------- helpers
    private AttendanceSetting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "근태 설정이 없습니다"));
    }

    private User loadUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<Long> scopedUsersForManagerNotification(Long requesterId) {
        User requester = loadUser(requesterId);
        if (requester.getDepartment() == null || requester.getDepartment().getManagerId() == null) {
            return List.of();
        }
        Long managerId = requester.getDepartment().getManagerId();
        return managerId.equals(requesterId) ? List.of() : List.of(managerId);
    }

    /** 역할별 조회 범위: 경영진/HR=전체, 팀장=본인 부서, 사원=본인. */
    private List<User> scopedUsers(AuthPrincipal principal) {
        Role role = principal.role();
        if (role == Role.CEO || role == Role.VP || role == Role.HR_MANAGER) {
            return userRepository.search(null, null, null);
        }
        if (role == Role.MANAGER) {
            User me = loadUser(principal.userId());
            Long deptId = me.getDepartment() != null ? me.getDepartment().getId() : -1L;
            return userRepository.search(deptId, null, null);
        }
        return List.of(loadUser(principal.userId()));
    }

    private Long managerIdOf(Long userId) {
        User user = loadUser(userId);
        return user.getDepartment() != null ? user.getDepartment().getManagerId() : null;
    }

    private Long departmentIdOf(Long userId) {
        User user = loadUser(userId);
        return user.getDepartment() != null ? user.getDepartment().getId() : null;
    }

    private void requireManageable(AuthPrincipal principal, Long targetUserId) {
        Role role = principal.role();
        if (role == Role.CEO || role == Role.VP || role == Role.HR_MANAGER) {
            return;
        }
        if (role == Role.MANAGER) {
            User me = loadUser(principal.userId());
            User target = loadUser(targetUserId);
            Long myDept = me.getDepartment() != null ? me.getDepartment().getId() : null;
            Long targetDept = target.getDepartment() != null ? target.getDepartment().getId() : null;
            if (myDept != null && myDept.equals(targetDept)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "해당 직원 근태를 처리할 권한이 없습니다");
    }
}
