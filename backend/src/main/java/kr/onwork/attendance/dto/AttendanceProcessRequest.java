package kr.onwork.attendance.dto;

import jakarta.validation.constraints.NotNull;

/** 시간외근로 승인/반려. REJECT 시 reason 필수. */
public record AttendanceProcessRequest(
        @NotNull Action action,
        String reason
) {
    public enum Action { APPROVE, REJECT }
}
