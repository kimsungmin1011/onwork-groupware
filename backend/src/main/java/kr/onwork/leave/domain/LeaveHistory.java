package kr.onwork.leave.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 휴가 잔여 변동 이력 (leave_histories) — 차감/복원 감사 추적. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "leave_histories")
public class LeaveHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "leave_balance_id", nullable = false)
    private Long leaveBalanceId;

    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private LeaveChangeType changeType;

    @Column(name = "change_days", nullable = false)
    private BigDecimal changeDays;

    @Column(name = "before_days", nullable = false)
    private BigDecimal beforeDays;

    @Column(name = "after_days", nullable = false)
    private BigDecimal afterDays;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    public static LeaveHistory of(Long balanceId, Long requestId, LeaveChangeType type,
                                  BigDecimal changeDays, BigDecimal before, BigDecimal after, Long changedBy) {
        LeaveHistory h = new LeaveHistory();
        h.leaveBalanceId = balanceId;
        h.leaveRequestId = requestId;
        h.changeType = type;
        h.changeDays = changeDays;
        h.beforeDays = before;
        h.afterDays = after;
        h.changedBy = changedBy;
        return h;
    }
}
