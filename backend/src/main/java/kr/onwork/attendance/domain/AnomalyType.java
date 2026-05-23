package kr.onwork.attendance.domain;

/** 근태 이상 유형 (work_anomalies.anomaly_type). */
public enum AnomalyType {
    LATE,                 // 지각
    EARLY_LEAVE,          // 조퇴
    ABSENT,               // 결근 (자정 배치 자동 감지, ADR-ATT-001)
    CLOCK_MISSING,        // 퇴근 누락
    UNAPPROVED_OVERTIME   // 미승인 시간외
}
