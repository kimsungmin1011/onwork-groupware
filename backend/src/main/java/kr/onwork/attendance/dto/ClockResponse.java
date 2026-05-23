package kr.onwork.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import kr.onwork.attendance.domain.DailyWorkRecord;

/** 오늘 근태 상태 / 출퇴근 결과 응답. */
public record ClockResponse(
        Long id,
        LocalDate date,
        LocalDateTime clockInAt,
        LocalDateTime clockOutAt,
        int overtimeMinutes,
        String status,
        boolean late,
        boolean earlyLeave
) {
    public static ClockResponse from(DailyWorkRecord r, boolean late, boolean earlyLeave) {
        return new ClockResponse(r.getId(), r.getDate(), r.getClockInAt(), r.getClockOutAt(),
                r.getOvertimeMinutes(), r.getStatus().name(), late, earlyLeave);
    }
}
