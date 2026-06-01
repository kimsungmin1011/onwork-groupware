package kr.onwork.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 대시보드 요약 위젯 데이터. */
public record DashboardSummary(
        String userName,
        String role,
        boolean clockedIn,
        LocalDateTime clockInAt,
        LocalDateTime clockOutAt,
        String attendanceStatus,
        BigDecimal annualTotal,
        BigDecimal annualUsed,
        BigDecimal annualRemaining,
        long unreadNotifications,
        int pendingApprovals,
        int teamAnomaliesToday,
        int monthWorkDays,
        int monthLateCount,
        int monthOvertimeMinutes
) {
}
