package kr.onwork.attendance.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.onwork.attendance.domain.DailyWorkRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyWorkRecordRepository extends JpaRepository<DailyWorkRecord, Long> {

    Optional<DailyWorkRecord> findByUserIdAndDate(Long userId, LocalDate date);

    List<DailyWorkRecord> findByDate(LocalDate date);

    List<DailyWorkRecord> findByUserIdInAndDate(List<Long> userIds, LocalDate date);

    List<DailyWorkRecord> findByUserIdAndDateBetweenOrderByDateDesc(Long userId, LocalDate from, LocalDate to);
}
