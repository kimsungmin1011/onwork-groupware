package kr.onwork.attendance.dto;

import java.time.LocalDate;

/** 근태 이상 항목 (팀 근태 현황). */
public record AnomalyResponse(
        Long id,
        Long userId,
        String userName,
        LocalDate date,
        String anomalyType,
        boolean confirmed
) {
}
