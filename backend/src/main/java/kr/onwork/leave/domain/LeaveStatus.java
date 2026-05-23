package kr.onwork.leave.domain;

/** 휴가 신청 상태 (leave_requests.status). ON_HOLD=보류(반려 대신, hold_reason 필수). */
public enum LeaveStatus {
    PENDING,
    APPROVED,
    ON_HOLD,
    CANCELLED
}
