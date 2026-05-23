package kr.onwork.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 근태 이상 (work_anomalies). 팀장 확인(confirmed_by) 전까지 미처리. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "work_anomalies")
public class WorkAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "daily_work_record_id", nullable = false)
    private Long dailyWorkRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_type", nullable = false, length = 30)
    private AnomalyType anomalyType;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    public static WorkAnomaly of(Long dailyWorkRecordId, AnomalyType type) {
        WorkAnomaly a = new WorkAnomaly();
        a.dailyWorkRecordId = dailyWorkRecordId;
        a.anomalyType = type;
        return a;
    }

    public boolean isConfirmed() {
        return confirmedBy != null;
    }

    public void confirm(Long managerId) {
        this.confirmedBy = managerId;
        this.confirmedAt = LocalDateTime.now();
    }
}
