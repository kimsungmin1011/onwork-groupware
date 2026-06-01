package kr.onwork.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import kr.onwork.schedule.domain.Schedule;

public record ScheduleResponse(
        Long id,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String title,
        String kind
) {
    public static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(s.getId(), s.getDate(), s.getStartTime(), s.getEndTime(), s.getTitle(), s.getKind());
    }
}
