package kr.onwork.leave.web;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.onwork.common.security.SecurityUtil;
import kr.onwork.leave.dto.LeaveBalanceResponse;
import kr.onwork.leave.dto.LeaveGrantRequest;
import kr.onwork.leave.dto.LeaveGrantResponse;
import kr.onwork.leave.dto.LeaveProcessRequest;
import kr.onwork.leave.dto.LeaveRequestCreate;
import kr.onwork.leave.dto.LeaveRequestResponse;
import kr.onwork.leave.service.LeaveService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LeaveCanonicalController {

    private final LeaveService leaveService;

    public LeaveCanonicalController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping("/leave-balances/me")
    public Map<String, Object> myBalances(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        List<LeaveBalanceResponse> items = leaveService.myBalances(SecurityUtil.currentPrincipal(), y);
        return Map.of("year", y, "items", items);
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse request(@Valid @RequestBody LeaveRequestCreate req) {
        return leaveService.request(SecurityUtil.currentPrincipal(), req);
    }

    @GetMapping("/leave-requests/me")
    public Map<String, Object> myRequests() {
        List<LeaveRequestResponse> items = leaveService.myRequests(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    @PatchMapping("/leave-requests/{id}/cancel")
    public LeaveRequestResponse cancel(@PathVariable Long id) {
        return leaveService.cancel(SecurityUtil.currentPrincipal(), id);
    }

    @PatchMapping("/leave-requests/{id}/cancel-approved")
    public LeaveRequestResponse cancelApproved(@PathVariable Long id) {
        return leaveService.cancelApproved(SecurityUtil.currentPrincipal(), id);
    }

    @PatchMapping("/leave-requests/{id}/process")
    public LeaveRequestResponse process(@PathVariable Long id, @Valid @RequestBody LeaveProcessRequest req) {
        return leaveService.process(SecurityUtil.currentPrincipal(), id, req);
    }

    @PreAuthorize("hasAnyRole('CEO','VP')")   // 경영진(CEO/VP)만 보상휴가 부여
    @PostMapping("/leave-grants")
    public LeaveGrantResponse grant(@Valid @RequestBody LeaveGrantRequest req) {
        return leaveService.grantCompLeave(SecurityUtil.currentPrincipal(), req);
    }

    @GetMapping("/leave-requests/summary")
    public Map<String, Object> summary() {
        return leaveService.summary(SecurityUtil.currentPrincipal());
    }
}
