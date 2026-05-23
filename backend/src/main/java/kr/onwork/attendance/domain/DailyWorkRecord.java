package kr.onwork.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 일일 근무 기록 (daily_work_records). 당일 1회(uq user_id,date), 서버 시각 기준. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "daily_work_records")
public class DailyWorkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "clock_in_at")
    private LocalDateTime clockInAt;

    @Column(name = "clock_out_at")
    private LocalDateTime clockOutAt;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkStatus status;

    public static DailyWorkRecord of(Long userId, LocalDate date) {
        DailyWorkRecord r = new DailyWorkRecord();
        r.userId = userId;
        r.date = date;
        r.status = WorkStatus.NORMAL;
        r.overtimeMinutes = 0;
        return r;
    }

    public void clockIn(LocalDateTime at, boolean anomaly) {
        this.clockInAt = at;
        if (anomaly) {
            this.status = WorkStatus.ANOMALY;
        }
    }

    public void clockOut(LocalDateTime at, int overtimeMinutes, boolean anomaly) {
        this.clockOutAt = at;
        this.overtimeMinutes = overtimeMinutes;
        if (anomaly) {
            this.status = WorkStatus.ANOMALY;
        }
    }

    public void markAnomaly() {
        this.status = WorkStatus.ANOMALY;
    }

    public boolean hasClockIn() {
        return clockInAt != null;
    }

    public boolean hasClockOut() {
        return clockOutAt != null;
    }
}
