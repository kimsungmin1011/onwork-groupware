package kr.onwork.leave.web;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.onwork.common.security.SecurityUtil;
import kr.onwork.leave.dto.LeaveBalanceResponse;
import kr.onwork.leave.dto.LeaveProcessRequest;
import kr.onwork.leave.dto.LeaveRequestCreate;
import kr.onwork.leave.dto.LeaveRequestResponse;
import kr.onwork.leave.service.LeaveService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 휴가관리 API (/api/v1/leave). */
@RestController
@RequestMapping("/api/v1/leave")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping("/balances")
    public Map<String, Object> balances(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        List<LeaveBalanceResponse> items = leaveService.myBalances(SecurityUtil.currentPrincipal(), y);
        return Map.of("year", y, "items", items);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse request(@Valid @RequestBody LeaveRequestCreate req) {
        return leaveService.request(SecurityUtil.currentPrincipal(), req);
    }

    @GetMapping("/requests")
    public Map<String, Object> myRequests() {
        List<LeaveRequestResponse> items = leaveService.myRequests(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    /** 휴가 결재함 (현재 유효 결재자 기준). */
    @GetMapping("/inbox")
    public Map<String, Object> inbox() {
        List<LeaveRequestResponse> items = leaveService.inbox(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    @PatchMapping("/requests/{id}/process")
    public LeaveRequestResponse process(@PathVariable Long id, @Valid @RequestBody LeaveProcessRequest req) {
        return leaveService.process(SecurityUtil.currentPrincipal(), id, req);
    }

    @PatchMapping("/requests/{id}/cancel")
    public LeaveRequestResponse cancel(@PathVariable Long id) {
        return leaveService.cancel(SecurityUtil.currentPrincipal(), id);
    }
}
