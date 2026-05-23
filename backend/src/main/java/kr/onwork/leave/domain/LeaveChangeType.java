package kr.onwork.leave.domain;

/** 휴가 잔여 변동 유형 (leave_histories.change_type). */
public enum LeaveChangeType {
    GRANT,   // 부여 (+)
    USE,     // 사용/차감 (-)
    CANCEL,  // 취소 복원 (+)
    EXPIRE   // 소멸 (-)
}
