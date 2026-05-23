package kr.onwork.leave.repository;

import java.util.Optional;
import kr.onwork.leave.domain.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {
    Optional<LeaveType> findByCode(String code);
}
