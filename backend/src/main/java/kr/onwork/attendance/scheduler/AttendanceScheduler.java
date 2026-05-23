package kr.onwork.attendance.scheduler;

import java.time.LocalDate;
import kr.onwork.attendance.service.AttendanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 근태 자정 배치 (ADR-ATT-001): 매일 00:00, 전일 출근 미기록 재직자에게 결근(ABSENT) 이상을 자동 생성.
 * 실시간 판정이 아닌 일배치로 처리해 출근 누락을 일관되게 감지한다.
 */
@Component
public class AttendanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttendanceScheduler.class);

    private final AttendanceService attendanceService;

    public AttendanceScheduler(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void detectYesterdayAbsences() {
        LocalDate target = LocalDate.now().minusDays(1);
        int created = attendanceService.detectAbsences(target);
        log.info("[근태배치] {} 결근/누락 이상 {}건 생성", target, created);
    }
}
