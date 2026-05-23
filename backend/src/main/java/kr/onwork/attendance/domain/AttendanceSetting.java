package kr.onwork.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 근태 정책 (attendance_settings, 싱글톤 id=1). 지각/조퇴 grace, 지각 임계치. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "attendance_settings")
public class AttendanceSetting {

    @Id
    private Long id;

    @Column(name = "grace_in_minutes", nullable = false)
    private int graceInMinutes;

    @Column(name = "grace_out_minutes", nullable = false)
    private int graceOutMinutes;

    @Column(name = "late_threshold_count", nullable = false)
    private int lateThresholdCount;

    @Column(name = "is_overtime_auto_collect", nullable = false)
    private boolean overtimeAutoCollect;
}
