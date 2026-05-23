package kr.onwork.leave.dto;

import jakarta.validation.constraints.NotNull;

/** 휴가 승인/보류. ON_HOLD 시 reason 필수. */
public record LeaveProcessRequest(
        @NotNull Action action,
        String reason
) {
    public enum Action { APPROVE, ON_HOLD }
}
