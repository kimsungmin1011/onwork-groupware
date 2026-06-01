package kr.onwork.hr.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import kr.onwork.common.domain.UserStatus;
import kr.onwork.common.security.SecurityUtil;
import kr.onwork.hr.domain.RequestStatus;
import kr.onwork.hr.dto.ChangeRequestResponse;
import kr.onwork.hr.dto.CreateChangeRequestRequest;
import kr.onwork.hr.dto.DepartmentResponse;
import kr.onwork.hr.dto.EmployeeResponse;
import kr.onwork.hr.dto.HrBatchProcessRequest;
import kr.onwork.hr.dto.HrBatchProcessResponse;
import kr.onwork.hr.dto.ProcessRequest;
import kr.onwork.hr.dto.SalaryResponse;
import kr.onwork.hr.service.HrService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인사관리 API (/api/v1/hr). */
@RestController
@RequestMapping("/api/v1/hr")
public class HrController {

    private final HrService hrService;

    public HrController(HrService hrService) {
        this.hrService = hrService;
    }

    /** 직원 목록 조회 (역할별 범위 차등). */
    @GetMapping("/employees")
    public Map<String, Object> listEmployees(
            @RequestParam(name = "department_id", required = false) Long departmentId,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String keyword) {
        List<EmployeeResponse> items =
                hrService.listEmployees(SecurityUtil.currentPrincipal(), departmentId, status, keyword);
        return Map.of("total", items.size(), "items", items);
    }

    /** 직원 상세 조회. */
    @GetMapping("/employees/{id}")
    public EmployeeResponse getEmployee(@PathVariable Long id) {
        return hrService.getEmployee(SecurityUtil.currentPrincipal(), id);
    }

    /** 내 급여 명세 — 본인만(마이페이지 전용). */
    @GetMapping("/salary/me")
    public SalaryResponse mySalary() {
        return hrService.mySalary(SecurityUtil.currentPrincipal());
    }

    /** 인사 변경 요청 등록 (입사/수정/퇴사 통합) — 경영지원팀(HR_MANAGER) 및 경영진(CEO/VP). */
    @PreAuthorize("hasAnyRole('CEO','VP','HR_MANAGER')")
    @PostMapping("/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestResponse create(@Valid @RequestBody CreateChangeRequestRequest req) {
        return hrService.createChangeRequest(SecurityUtil.currentPrincipal(), req);
    }

    /** 인사 변경 요청 목록 (결재함). */
    @GetMapping("/change-requests")
    public Map<String, Object> listChangeRequests(@RequestParam(required = false) RequestStatus status) {
        List<ChangeRequestResponse> items =
                hrService.listChangeRequests(SecurityUtil.currentPrincipal(), status);
        return Map.of("total", items.size(), "items", items);
    }

    /** 인사 변경 요청 승인/반려 — 경영진(VP 이상). */
    @PreAuthorize("hasRole('VP')")
    @PatchMapping("/change-requests/{id}/process")
    public ChangeRequestResponse process(@PathVariable Long id, @Valid @RequestBody ProcessRequest req) {
        return hrService.process(SecurityUtil.currentPrincipal(), id, req);
    }

    /** 0529 명세: 인사 변경 요청 일괄 승인. 최대 50건, 부분 성공, batch_id 감사 추적. */
    @PreAuthorize("hasRole('VP')")
    @PostMapping("/change-requests/batch-process")
    public HrBatchProcessResponse batchProcess(@Valid @RequestBody HrBatchProcessRequest req) {
        return hrService.batchProcess(SecurityUtil.currentPrincipal(), req);
    }

    // ---------- 임시저장 (UC-HR-01 A1) ----------
    @PreAuthorize("hasRole('HR_MANAGER')")
    @PostMapping("/change-requests/draft")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestResponse createDraft(@Valid @RequestBody CreateChangeRequestRequest req) {
        return hrService.createDraft(SecurityUtil.currentPrincipal(), req);
    }

    @PreAuthorize("hasRole('HR_MANAGER')")
    @PatchMapping("/change-requests/{id}/draft")
    public ChangeRequestResponse updateDraft(@PathVariable Long id,
                                             @Valid @RequestBody CreateChangeRequestRequest req) {
        return hrService.updateDraft(SecurityUtil.currentPrincipal(), id, req);
    }

    /** DRAFT → PENDING 승인 요청. */
    @PreAuthorize("hasRole('HR_MANAGER')")
    @PostMapping("/change-requests/{id}/submit")
    public ChangeRequestResponse submitDraft(@PathVariable Long id) {
        return hrService.submitDraft(SecurityUtil.currentPrincipal(), id);
    }

    @PreAuthorize("hasRole('HR_MANAGER')")
    @DeleteMapping("/change-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable Long id) {
        hrService.deleteDraft(SecurityUtil.currentPrincipal(), id);
    }

    /** 내 임시저장 목록 (HR_MANAGER 본인). */
    @PreAuthorize("hasRole('HR_MANAGER')")
    @GetMapping("/change-requests/my-drafts")
    public Map<String, Object> listMyDrafts() {
        List<ChangeRequestResponse> items = hrService.listMyDrafts(SecurityUtil.currentPrincipal());
        return Map.of("total", items.size(), "items", items);
    }

    /** 폼 초기화 시 자동 채번된 임시 사번 (UC-HR-01 정상 흐름 2단계). HR_MANAGER 및 경영진. */
    @PreAuthorize("hasAnyRole('CEO','VP','HR_MANAGER')")
    @GetMapping("/change-requests/next-employee-no")
    public Map<String, String> suggestEmployeeNo() {
        return Map.of("employee_no", hrService.suggestNextEmployeeNo());
    }

    /** 부서 드롭다운(미분류 옵션은 프론트가 추가). */
    @GetMapping("/departments")
    public Map<String, Object> listDepartments() {
        List<DepartmentResponse> items = hrService.listDepartments();
        return Map.of("items", items);
    }
}
