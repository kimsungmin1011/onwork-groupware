package kr.onwork.dashboard.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.onwork.attendance.domain.AnomalyType;
import kr.onwork.attendance.domain.DailyWorkRecord;
import kr.onwork.attendance.repository.DailyWorkRecordRepository;
import kr.onwork.attendance.repository.WorkAnomalyRepository;
import kr.onwork.attendance.service.AttendanceService;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.repository.UserRepository;
import kr.onwork.common.security.AuthPrincipal;
import kr.onwork.dashboard.dto.DashboardSummary;
import kr.onwork.hr.domain.RequestStatus;
import kr.onwork.hr.repository.HrChangeRequestRepository;
import kr.onwork.leave.repository.LeaveBalanceRepository;
import kr.onwork.leave.service.LeaveService;
import kr.onwork.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대시보드 위젯 데이터 집계 — 각 모듈 서비스/리포지토리를 조합. */
@Service
public class DashboardService {

    private static final long ANNUAL_TYPE_ID = 1L;

    private final DailyWorkRecordRepository recordRepository;
    private final WorkAnomalyRepository anomalyRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final HrChangeRequestRepository hrRepository;
    private final NotificationService notificationService;
    private final LeaveService leaveService;
    private final AttendanceService attendanceService;
    private final UserRepository userRepository;

    public DashboardService(DailyWorkRecordRepository recordRepository,
                            WorkAnomalyRepository anomalyRepository,
                            LeaveBalanceRepository balanceRepository,
                            HrChangeRequestRepository hrRepository,
                            NotificationService notificationService,
                            LeaveService leaveService,
                            AttendanceService attendanceService,
                            UserRepository userRepository) {
        this.recordRepository = recordRepository;
        this.anomalyRepository = anomalyRepository;
        this.balanceRepository = balanceRepository;
        this.hrRepository = hrRepository;
        this.notificationService = notificationService;
        this.leaveService = leaveService;
        this.attendanceService = attendanceService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(AuthPrincipal principal) {
        Long uid = principal.userId();
        LocalDate today = LocalDate.now();
        User me = userRepository.findById(uid).orElse(null);

        DailyWorkRecord rec = recordRepository.findByUserIdAndDate(uid, today).orElse(null);
        boolean clockedIn = rec != null && rec.hasClockIn();

        var balance = balanceRepository
                .findByUserIdAndLeaveTypeIdAndYear(uid, ANNUAL_TYPE_ID, (short) today.getYear())
                .orElse(null);
        BigDecimal total = balance != null ? balance.getTotalDays() : BigDecimal.ZERO;
        BigDecimal used = balance != null ? balance.getUsedDays() : BigDecimal.ZERO;
        BigDecimal remaining = balance != null ? balance.remaining() : BigDecimal.ZERO;

        boolean managerUp = principal.role() == Role.MANAGER || principal.role() == Role.HR_MANAGER
                || principal.role() == Role.VP || principal.role() == Role.CEO;
        boolean exec = principal.role() == Role.CEO || principal.role() == Role.VP;

        int pending = leaveService.inbox(principal).size()
                + attendanceService.overtimeInbox(principal).size()
                + (exec ? hrRepository.findByStatusOrderByIdDesc(RequestStatus.PENDING).size() : 0);
        int teamAnomalies = managerUp ? attendanceService.listAnomalies(principal, today).size() : 0;

        // 이번 달 근태 요약: 출근일 / 지각 횟수 / 초과 근무(분)
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
        List<DailyWorkRecord> monthRecords =
                recordRepository.findByUserIdAndDateBetweenOrderByDateDesc(uid, monthStart, monthEnd);
        int monthWorkDays = (int) monthRecords.stream().filter(DailyWorkRecord::hasClockIn).count();
        int monthOvertime = monthRecords.stream().mapToInt(DailyWorkRecord::getOvertimeMinutes).sum();
        int monthLate = 0;
        List<Long> recordIds = monthRecords.stream().map(DailyWorkRecord::getId).toList();
        if (!recordIds.isEmpty()) {
            monthLate = (int) anomalyRepository.findByDailyWorkRecordIdIn(recordIds).stream()
                    .filter(a -> a.getAnomalyType() == AnomalyType.LATE).count();
        }

        return new DashboardSummary(
                me != null ? me.getName() : "",
                principal.role().name(),
                clockedIn,
                rec != null ? rec.getClockInAt() : null,
                rec != null ? rec.getClockOutAt() : null,
                rec != null ? rec.getStatus().name() : "NONE",
                total, used, remaining,
                notificationService.unreadCount(uid),
                pending,
                teamAnomalies,
                monthWorkDays,
                monthLate,
                monthOvertime
        );
    }
}
