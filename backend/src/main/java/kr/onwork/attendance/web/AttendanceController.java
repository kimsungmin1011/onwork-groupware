package kr.onwork.attendance.web;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.onwork.attendance.dto.AnomalyResponse;
import kr.onwork.attendance.dto.AttendanceProcessRequest;
import kr.onwork.attendance.dto.ClockResponse;
import kr.onwork.attendance.dto.OvertimeCreateRequest;
import kr.onwork.attendance.dto.OvertimeResponse;
import kr.onwork.attendance.service.AttendanceService;
import kr.onwork.common.security.SecurityUtil;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 근태관리 API (/api/v1/attendance). */
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/clock-in")
    public ClockResponse clockIn() {
        return attendanceService.clockIn(SecurityUtil.currentPrincipal());
    }

    @PostMapping("/clock-out")
    public ClockResponse clockOut() {
        return attendanceService.clockOut(SecurityUtil.currentPrincipal());
    }

    @GetMapping("/today")
    public ClockResponse today() {
        return attendanceService.today(SecurityUtil.currentPrincipal());
    }

    /** 팀 근태 이상 목록 (팀장 본인 부서 / 경영진·HR 전체). */
    @GetMapping("/anomalies")
    public Map<String, Object> anomalies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        List<AnomalyResponse> items = attendanceService.listAnomalies(SecurityUtil.currentPrincipal(), target);
        return Map.of("date", target.toString(), "total", items.size(), "items", items);
    }

    @PatchMapping("/anomalies/{id}/confirm")
    public void confirmAnomaly(@PathVariable Long id) {
        attendanceService.confirmAnomaly(SecurityUtil.currentPrincipal(), id);
    }

    /** 시간외근로 신청. */
    @PostMapping("/overtime")
    public OvertimeResponse requestOvertime(@Valid @RequestBody OvertimeCreateRequest req) {
        return attendanceService.requestOvertime(SecurityUtil.currentPrincipal(), req);
    }

    @GetMapping("/overtime")
    public Map<String, Object> myOvertime() {
        List<OvertimeResponse> items = attendanceService.myOvertime(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    /** 팀 시간외근로 결재함 (팀장+). */
    @PreAuthorize("hasRole('MANAGER')")
    @GetMapping("/overtime/inbox")
    public Map<String, Object> overtimeInbox() {
        List<OvertimeResponse> items = attendanceService.overtimeInbox(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    @PreAuthorize("hasRole('MANAGER')")
    @PatchMapping("/overtime/{id}/process")
    public OvertimeResponse processOvertime(@PathVariable Long id, @Valid @RequestBody AttendanceProcessRequest req) {
        return attendanceService.processOvertime(SecurityUtil.currentPrincipal(), id, req);
    }

    /** 운영용: 결근 감지 배치 수동 재실행 (스케줄러와 동일 로직, ADR-ATT-001). CEO 전용. */
    @PreAuthorize("hasRole('CEO')")
    @PostMapping("/admin/detect-absences")
    public Map<String, Object> detectAbsences(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int created = attendanceService.detectAbsences(date);
        return Map.of("date", date.toString(), "created", created);
    }
}
