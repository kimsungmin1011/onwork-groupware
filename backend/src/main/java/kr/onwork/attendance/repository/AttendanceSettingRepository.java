package kr.onwork.attendance.repository;

import kr.onwork.attendance.domain.AttendanceSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceSettingRepository extends JpaRepository<AttendanceSetting, Long> {
}
