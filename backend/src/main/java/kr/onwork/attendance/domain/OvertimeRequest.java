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

/** 시간외근로 신청 (overtime_requests). 팀장 승인 시 인정. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "overtime_requests")
public class OvertimeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "expected_start_at", nullable = false)
    private LocalDateTime expectedStartAt;

    @Column(name = "expected_end_at", nullable = false)
    private LocalDateTime expectedEndAt;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OvertimeStatus status;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    public static OvertimeRequest create(Long userId, LocalDate requestDate,
                                         LocalDateTime start, LocalDateTime end, String reason) {
        OvertimeRequest r = new OvertimeRequest();
        r.userId = userId;
        r.requestDate = requestDate;
        r.expectedStartAt = start;
        r.expectedEndAt = end;
        r.reason = reason;
        r.status = OvertimeStatus.PENDING;
        return r;
    }

    public boolean isPending() {
        return status == OvertimeStatus.PENDING;
    }

    public void approve(Long approverId) {
        this.status = OvertimeStatus.APPROVED;
        this.approverId = approverId;
    }

    public void reject(Long approverId, String rejectReason) {
        this.status = OvertimeStatus.REJECTED;
        this.approverId = approverId;
        this.rejectReason = rejectReason;
    }
}
