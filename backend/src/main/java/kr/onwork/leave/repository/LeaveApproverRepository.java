package kr.onwork.leave.repository;

import java.util.List;
import java.util.Optional;
import kr.onwork.leave.domain.LeaveApprover;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveApproverRepository extends JpaRepository<LeaveApprover, Long> {

    Optional<LeaveApprover> findByDepartmentId(Long departmentId);

    /** 내가 현재 유효 결재자인 부서 설정 (팀장이며 비부재 또는 대행이며 부재). */
    List<LeaveApprover> findByApproverIdOrDelegateId(Long approverId, Long delegateId);
}
