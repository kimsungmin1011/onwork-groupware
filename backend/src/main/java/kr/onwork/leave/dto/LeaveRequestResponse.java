package kr.onwork.leave.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.onwork.leave.domain.LeaveRequest;

public record LeaveRequestResponse(
        Long id,
        Long userId,
        String userName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal daysUsed,
        String reason,
        String status,
        Long approverId,
        boolean delegated,
        String holdReason
) {
    public static LeaveRequestResponse of(LeaveRequest r, String userName) {
        return new LeaveRequestResponse(r.getId(), r.getUserId(), userName,
                r.getStartDate(), r.getEndDate(), r.getDaysUsed(), r.getReason(),
                r.getStatus().name(), r.getApproverId(), r.isDelegated(), r.getHoldReason());
    }
}
