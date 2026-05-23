package kr.onwork.leave.repository;

import java.util.List;
import java.util.Optional;
import kr.onwork.leave.domain.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    List<LeaveBalance> findByUserIdAndYear(Long userId, short year);

    Optional<LeaveBalance> findByUserIdAndLeaveTypeIdAndYear(Long userId, Long leaveTypeId, short year);
}
