package kr.onwork.attendance.repository;

import java.util.List;
import kr.onwork.attendance.domain.AnomalyType;
import kr.onwork.attendance.domain.WorkAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkAnomalyRepository extends JpaRepository<WorkAnomaly, Long> {

    List<WorkAnomaly> findByDailyWorkRecordIdIn(List<Long> recordIds);

    boolean existsByDailyWorkRecordIdAndAnomalyType(Long dailyWorkRecordId, AnomalyType type);
}
