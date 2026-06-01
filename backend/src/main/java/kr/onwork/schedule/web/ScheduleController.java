package kr.onwork.schedule.web;

import java.util.List;
import java.util.Map;
import kr.onwork.common.security.SecurityUtil;
import kr.onwork.schedule.dto.ScheduleResponse;
import kr.onwork.schedule.service.ScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 오늘의 일정(오늘부터 days일, 기본 3일). */
    @GetMapping("/schedules/me")
    public Map<String, Object> mySchedules(@RequestParam(required = false, defaultValue = "3") int days) {
        List<ScheduleResponse> items = scheduleService.myUpcoming(SecurityUtil.currentPrincipal(), days);
        return Map.of("total", items.size(), "items", items);
    }
}
