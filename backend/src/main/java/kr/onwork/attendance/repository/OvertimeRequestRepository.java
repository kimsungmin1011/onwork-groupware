package kr.onwork.attendance.repository;

import java.util.List;
import kr.onwork.attendance.domain.OvertimeRequest;
import kr.onwork.attendance.domain.OvertimeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OvertimeRequestRepository extends JpaRepository<OvertimeRequest, Long> {

    List<OvertimeRequest> findByUserIdOrderByIdDesc(Long userId);

    List<OvertimeRequest> findByUserIdInAndStatusOrderByIdDesc(List<Long> userIds, OvertimeStatus status);
}
