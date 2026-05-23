package kr.onwork.leave.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 부서별 휴가 결재자 + 대행자 (leave_approvers, ADR-003). is_absent=TRUE 시 delegate로 자동 전환. */
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "leave_approvers")
public class LeaveApprover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    @Column(name = "approver_id", nullable = false)
    private Long approverId;

    @Column(name = "delegate_id")
    private Long delegateId;

    @Column(name = "is_absent", nullable = false)
    private boolean absent;

    /** 현재 유효 결재자 — 부재 중이면 대행자, 아니면 팀장. */
    public Long activeApproverId() {
        return (absent && delegateId != null) ? delegateId : approverId;
    }
}
