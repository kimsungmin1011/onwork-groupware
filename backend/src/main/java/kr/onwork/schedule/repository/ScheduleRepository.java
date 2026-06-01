package kr.onwork.schedule.repository;

import java.time.LocalDate;
import java.util.List;
import kr.onwork.schedule.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /** 특정 사용자의 [from, to] 기간 일정 — 날짜·시작시간 순. */
    List<Schedule> findByUserIdAndDateBetweenOrderByDateAscStartTimeAsc(Long userId, LocalDate from, LocalDate to);
}
