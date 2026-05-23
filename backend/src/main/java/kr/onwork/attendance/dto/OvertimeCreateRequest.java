package kr.onwork.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record OvertimeCreateRequest(
        @NotNull LocalDate requestDate,
        @NotNull LocalDateTime expectedStartAt,
        @NotNull LocalDateTime expectedEndAt,
        @NotBlank String reason
) {
}
