package kr.onwork.hr.repository;

import java.util.Optional;
import kr.onwork.hr.domain.Salary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryRepository extends JpaRepository<Salary, Long> {

    Optional<Salary> findByUserId(Long userId);
}
