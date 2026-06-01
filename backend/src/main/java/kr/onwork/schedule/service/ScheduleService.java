package kr.onwork.schedule.service;

import java.time.LocalDate;
import java.util.List;
import kr.onwork.common.security.AuthPrincipal;
import kr.onwork.schedule.dto.ScheduleResponse;
import kr.onwork.schedule.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 오늘의 일정 조회. 본인 일정만. */
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;

    public ScheduleService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /** 오늘부터 향후 days일간의 내 일정(기본 3일). */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> myUpcoming(AuthPrincipal principal, int days) {
        LocalDate today = LocalDate.now();
        int span = days <= 0 ? 3 : days;
        return scheduleRepository
                .findByUserIdAndDateBetweenOrderByDateAscStartTimeAsc(principal.userId(), today, today.plusDays(span - 1L))
                .stream().map(ScheduleResponse::from).toList();
    }
}
