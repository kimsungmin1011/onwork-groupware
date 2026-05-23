package kr.onwork.leave.dto;

import java.math.BigDecimal;

public record LeaveBalanceResponse(
        Long id,
        Long leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        BigDecimal totalDays,
        BigDecimal usedDays,
        BigDecimal remainingDays,
        int year
) {
}
