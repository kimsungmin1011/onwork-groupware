package kr.onwork.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import kr.onwork.attendance.domain.OvertimeRequest;

public record OvertimeResponse(
        Long id,
        Long userId,
        LocalDate requestDate,
        LocalDateTime expectedStartAt,
        LocalDateTime expectedEndAt,
        String reason,
        String status,
        Long approverId,
        String rejectReason
) {
    public static OvertimeResponse from(OvertimeRequest r) {
        return new OvertimeResponse(r.getId(), r.getUserId(), r.getRequestDate(),
                r.getExpectedStartAt(), r.getExpectedEndAt(), r.getReason(),
                r.getStatus().name(), r.getApproverId(), r.getRejectReason());
    }
}
