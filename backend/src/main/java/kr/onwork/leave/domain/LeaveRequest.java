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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 휴가 신청 (leave_requests). 신청 시 차감 보류, 승인 시 차감(ADR-LEAVE), 대행 처리 시 is_delegated. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "leave_balance_id", nullable = false)
    private Long leaveBalanceId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "days_used", nullable = false)
    private BigDecimal daysUsed;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveStatus status;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "is_delegated", nullable = false)
    private boolean delegated;

    @Column(name = "hold_reason", length = 500)
    private String holdReason;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public static LeaveRequest create(Long userId, Long leaveBalanceId, LocalDate start, LocalDate end,
                                      BigDecimal daysUsed, String reason) {
        LeaveRequest r = new LeaveRequest();
        r.userId = userId;
        r.leaveBalanceId = leaveBalanceId;
        r.startDate = start;
        r.endDate = end;
        r.daysUsed = daysUsed;
        r.reason = reason;
        r.status = LeaveStatus.PENDING;
        r.delegated = false;
        return r;
    }

    public boolean isPending() {
        return status == LeaveStatus.PENDING;
    }

    public void approve(Long approverId, boolean delegated) {
        this.status = LeaveStatus.APPROVED;
        this.approverId = approverId;
        this.delegated = delegated;
        this.approvedAt = LocalDateTime.now();
    }

    public void hold(Long approverId, String holdReason) {
        this.status = LeaveStatus.ON_HOLD;
        this.approverId = approverId;
        this.holdReason = holdReason;
    }

    public void cancel() {
        this.status = LeaveStatus.CANCELLED;
    }
}
