package kr.onwork.leave.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record LeaveRequestCreate(
        @NotNull Long leaveTypeId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String reason
) {
}
