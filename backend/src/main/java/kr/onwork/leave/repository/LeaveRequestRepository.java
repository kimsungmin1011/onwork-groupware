package kr.onwork.leave.repository;

import java.time.LocalDate;
import java.util.List;
import kr.onwork.leave.domain.LeaveRequest;
import kr.onwork.leave.domain.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByUserIdOrderByIdDesc(Long userId);

    List<LeaveRequest> findByUserIdInAndStatusOrderByIdDesc(List<Long> userIds, LeaveStatus status);

    /** 기간 중복 검사 — 동일 사용자의 PENDING/APPROVED 신청과 겹치는지. */
    @Query("""
            select count(r) from LeaveRequest r
            where r.userId = :userId
              and r.status in (kr.onwork.leave.domain.LeaveStatus.PENDING, kr.onwork.leave.domain.LeaveStatus.APPROVED)
              and r.startDate <= :end and r.endDate >= :start
            """)
    long countOverlapping(@Param("userId") Long userId,
                          @Param("start") LocalDate start,
                          @Param("end") LocalDate end);
}
