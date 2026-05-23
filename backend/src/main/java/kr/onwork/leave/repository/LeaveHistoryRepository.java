package kr.onwork.leave.repository;

import kr.onwork.leave.domain.LeaveHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveHistoryRepository extends JpaRepository<LeaveHistory, Long> {
}
