package kr.onwork.hr.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.onwork.approval.service.ApprovalRoutingService;
import kr.onwork.common.domain.Department;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.domain.UserStatus;
import kr.onwork.common.domain.WorkGroup;
import kr.onwork.common.error.BusinessException;
import kr.onwork.common.error.ErrorCode;
import kr.onwork.common.repository.DepartmentRepository;
import kr.onwork.common.repository.UserCredentialRepository;
import kr.onwork.common.repository.UserRepository;
import kr.onwork.common.repository.WorkGroupRepository;
import kr.onwork.common.security.AuthPrincipal;
import kr.onwork.hr.domain.ChangeType;
import kr.onwork.hr.domain.EmployeeChangeHistory;
import kr.onwork.hr.domain.HrChangeRequest;
import kr.onwork.hr.domain.RequestStatus;
import kr.onwork.hr.domain.Salary;
import kr.onwork.hr.dto.ChangeRequestResponse;
import kr.onwork.hr.dto.CreateChangeRequestRequest;
import kr.onwork.hr.dto.DepartmentResponse;
import kr.onwork.hr.dto.EmployeeResponse;
import kr.onwork.hr.dto.HrBatchProcessRequest;
import kr.onwork.hr.dto.HrBatchProcessResponse;
import kr.onwork.hr.dto.ProcessRequest;
import kr.onwork.hr.dto.SalaryResponse;
import kr.onwork.hr.repository.EmployeeChangeHistoryRepository;
import kr.onwork.hr.repository.HrChangeRequestRepository;
import kr.onwork.hr.repository.SalaryRepository;
import kr.onwork.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인사(HR) 비즈니스 로직. 핵심 원칙(ADR-HR-001): 승인 전 실데이터(users) 미반영.
 * change_type별 처리 핸들러 분기(ADR-HR-001 OCP), 반려 사유 필수(ADR-HR-002).
 */
@Service
public class HrService {

    private static final Logger log = LoggerFactory.getLogger(HrService.class);
    private static final long DEFAULT_WORK_GROUP_ID = 1L;

    private final HrChangeRequestRepository changeRequestRepository;
    private final EmployeeChangeHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final DepartmentRepository departmentRepository;
    private final WorkGroupRepository workGroupRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final ApprovalRoutingService approvalRoutingService;
    private final SalaryRepository salaryRepository;

    public HrService(HrChangeRequestRepository changeRequestRepository,
                     EmployeeChangeHistoryRepository historyRepository,
                     UserRepository userRepository,
                     UserCredentialRepository credentialRepository,
                     DepartmentRepository departmentRepository,
                     WorkGroupRepository workGroupRepository,
                     PasswordEncoder passwordEncoder,
                     NotificationService notificationService,
                     ApprovalRoutingService approvalRoutingService,
                     SalaryRepository salaryRepository) {
        this.changeRequestRepository = changeRequestRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.departmentRepository = departmentRepository;
        this.workGroupRepository = workGroupRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.approvalRoutingService = approvalRoutingService;
        this.salaryRepository = salaryRepository;
    }

    /** 내 급여 명세(마이페이지 전용). 본인 데이터만 조회. */
    @Transactional(readOnly = true)
    public SalaryResponse mySalary(AuthPrincipal principal) {
        Salary salary = salaryRepository.findByUserId(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "급여 정보가 없습니다"));
        return SalaryResponse.from(salary);
    }

    // ---------------------------------------------------------------- 요청 등록 (즉시 PENDING)
    @Transactional
    public ChangeRequestResponse createChangeRequest(AuthPrincipal principal, CreateChangeRequestRequest req) {
        validateCreatePayload(req);
        HrChangeRequest saved = changeRequestRepository.save(
                HrChangeRequest.create(req.changeType(), req.targetUserId(),
                        req.payload(), req.reason(), principal.userId()));
        // 승인자(경영진)에게 알림 — UC-HR-01/03 사후조건
        notifyApprovers(saved);
        approvalRoutingService.open(ApprovalRoutingService.TYPE_HR, saved.getId(),
                principal.userId(), null, departmentIdOf(principal.userId()));
        return ChangeRequestResponse.from(saved);
    }

    // ---------------------------------------------------------------- 임시저장 (UC-HR-01 A1)
    @Transactional
    public ChangeRequestResponse createDraft(AuthPrincipal principal, CreateChangeRequestRequest req) {
        // 임시저장 시점엔 필수값 검증을 건너뜀 (작성 중일 수 있음). 알림 미발송.
        HrChangeRequest saved = changeRequestRepository.save(HrChangeRequest.createDraft(
                req.changeType(), req.targetUserId(), req.payload(), req.reason(), principal.userId()));
        return ChangeRequestResponse.from(saved);
    }

    @Transactional
    public ChangeRequestResponse updateDraft(AuthPrincipal principal, Long id, CreateChangeRequestRequest req) {
        HrChangeRequest draft = ownDraft(principal, id);
        draft.updateDraft(req.changeType(), req.targetUserId(), req.payload(), req.reason());
        return ChangeRequestResponse.from(draft);
    }

    /** DRAFT → PENDING 전환 + 정식 검증 + 승인자 알림. */
    @Transactional
    public ChangeRequestResponse submitDraft(AuthPrincipal principal, Long id) {
        HrChangeRequest draft = ownDraft(principal, id);
        validateForSubmit(draft.getChangeType(), draft.getTargetUserId(), draft.getPayload());
        draft.submit();
        notifyApprovers(draft);
        return ChangeRequestResponse.from(draft);
    }

    @Transactional
    public void deleteDraft(AuthPrincipal principal, Long id) {
        HrChangeRequest draft = ownDraft(principal, id);
        changeRequestRepository.delete(draft);
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestResponse> listMyDrafts(AuthPrincipal principal) {
        return changeRequestRepository
                .findByRequestedByAndStatusOrderByIdDesc(principal.userId(), RequestStatus.DRAFT)
                .stream().map(ChangeRequestResponse::from).toList();
    }

    /** UC-HR-01 정상 흐름 2단계: 자동 채번된 임시 사번을 폼에 미리 표시. */
    @Transactional(readOnly = true)
    public String suggestNextEmployeeNo() {
        return generateEmployeeNo();
    }

    /** 입사 폼 부서 드롭다운(미분류 옵션은 프론트가 추가). */
    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        return departmentRepository.findAll().stream()
                .map(d -> new DepartmentResponse(d.getId(), d.getName()))
                .toList();
    }

    private HrChangeRequest ownDraft(AuthPrincipal principal, Long id) {
        HrChangeRequest draft = changeRequestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!draft.getRequestedBy().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 임시저장만 처리 가능합니다");
        }
        if (!draft.isDraft()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "임시저장 상태가 아닙니다");
        }
        return draft;
    }

    private void validateCreatePayload(CreateChangeRequestRequest req) {
        validateForSubmit(req.changeType(), req.targetUserId(), req.payload());
    }

    /** UC-HR-01 정상 흐름 4단계 검증 — DRAFT 승인 요청(submit) 시점에도 동일하게 적용. */
    private void validateForSubmit(ChangeType type, Long targetUserId, Map<String, Object> p) {
        switch (type) {
            case CREATE -> {
                requireField(p, "name");
                requireField(p, "email");
                requireField(p, "hire_date");
                String email = asString(p.get("email"));
                if (userRepository.existsByEmail(email)) {
                    throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
                }
            }
            case UPDATE -> {
                if (targetUserId == null) {
                    throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "target_user_id가 필요합니다");
                }
                userRepository.findById(targetUserId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            }
            case RESIGN -> {
                if (targetUserId == null) {
                    throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "target_user_id가 필요합니다");
                }
                User target = userRepository.findById(targetUserId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                if (target.isResigned()) {
                    throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 퇴사 처리된 직원입니다");
                }
                requireField(p, "resign_reason");          // US-006 인수조건
                LocalDate resignDate = asDate(p.get("resign_date"));
                if (resignDate == null || resignDate.isBefore(LocalDate.now())) {
                    throw new BusinessException(ErrorCode.INVALID_RESIGN_DATE);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 결재함 조회
    @Transactional(readOnly = true)
    public List<ChangeRequestResponse> listChangeRequests(AuthPrincipal principal, RequestStatus status) {
        List<HrChangeRequest> list;
        if (isExecutive(principal.role())) {
            list = (status != null)
                    ? changeRequestRepository.findByStatusOrderByIdDesc(status)
                    : changeRequestRepository.findAllByOrderByIdDesc();
        } else if (principal.role() == Role.HR_MANAGER) {
            list = changeRequestRepository.findByRequestedByOrderByIdDesc(principal.userId());
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return list.stream().map(ChangeRequestResponse::from).toList();
    }

    // ---------------------------------------------------------------- 승인/반려 처리
    @Transactional
    public ChangeRequestResponse process(AuthPrincipal principal, Long requestId, ProcessRequest req) {
        HrChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.getRequestedBy().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.CANNOT_SELF_APPROVE);   // UC-HR-02 E2
        }

        if (req.action() == ProcessRequest.Action.REJECT) {
            if (req.reason() == null || req.reason().isBlank()) {         // ADR-HR-002 반려 사유 필수
                throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "반려 사유는 필수입니다");
            }
            request.reject(principal.userId(), req.reason());
            approvalRoutingService.complete(ApprovalRoutingService.TYPE_HR, request.getId(),
                    principal.userId(), "REJECT", req.reason());
            notificationService.notify(request.getRequestedBy(), NotificationService.HR_CHANGE_REJECTED,
                    "HR", request.getId(), "인사 변경 요청이 반려되었습니다: " + req.reason());
            return ChangeRequestResponse.from(request);
        }

        // APPROVE — change_type별 실데이터 반영
        applyApproval(request, principal.userId());
        request.approve(principal.userId());
        approvalRoutingService.complete(ApprovalRoutingService.TYPE_HR, request.getId(),
                principal.userId(), "APPROVE", null);
        notificationService.notify(request.getRequestedBy(), NotificationService.HR_CHANGE_APPROVED,
                "HR", request.getId(), "인사 변경 요청이 승인되었습니다");
        return ChangeRequestResponse.from(request);
    }

    @Transactional
    public HrBatchProcessResponse batchProcess(AuthPrincipal principal, HrBatchProcessRequest req) {
        UUID batchId = UUID.randomUUID();
        List<HrBatchProcessResponse.Result> results = new ArrayList<>();
        int success = 0;
        int failure = 0;
        for (Long id : req.ids()) {
            try {
                HrChangeRequest target = changeRequestRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                target.assignBatch(batchId.toString());
                process(principal, id, new ProcessRequest(ProcessRequest.Action.APPROVE, null));
                results.add(new HrBatchProcessResponse.Result(id, true, null));
                success++;
            } catch (BusinessException e) {
                results.add(new HrBatchProcessResponse.Result(id, false,
                        e.getErrorCode().name() + ": " + e.getMessage()));
                failure++;
            } catch (RuntimeException e) {
                results.add(new HrBatchProcessResponse.Result(id, false, e.getMessage()));
                failure++;
            }
        }
        log.info("[HR batch] batchId={} approver={} total={} success={} failure={}",
                batchId, principal.userId(), req.ids().size(), success, failure);
        return new HrBatchProcessResponse(batchId, req.ids().size(), success, failure, results);
    }

    private void applyApproval(HrChangeRequest request, Long approverId) {
        Map<String, Object> p = request.getPayload();
        switch (request.getChangeType()) {
            case CREATE -> {
                User created = applyCreate(p);
                history(created.getId(), ChangeType.CREATE, null, snapshot(created), request.getId(), approverId);
            }
            case UPDATE -> {
                User target = userRepository.findById(request.getTargetUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                Map<String, Object> before = snapshot(target);
                Department dept = resolveDepartment(p.get("department_id"));
                target.applyUpdate(dept, asString(p.get("position")), asRole(p.get("role")));
                history(target.getId(), ChangeType.UPDATE, before, snapshot(target), request.getId(), approverId);
            }
            case RESIGN -> {
                User target = userRepository.findById(request.getTargetUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                Map<String, Object> before = snapshot(target);
                target.resign(asDate(p.get("resign_date")), asString(p.get("resign_reason")));
                history(target.getId(), ChangeType.RESIGN, before, snapshot(target), request.getId(), approverId);
            }
        }
    }

    /** CREATE 승인: users + user_credentials 생성, 계정 활성화 (UC-HR-02 A2). */
    private User applyCreate(Map<String, Object> p) {
        String email = asString(p.get("email"));
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        Department dept = resolveDepartment(p.get("department_id"));
        WorkGroup wg = workGroupRepository.findById(workGroupId(p))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PAYLOAD, "근무그룹을 찾을 수 없습니다"));
        String employeeNo = generateEmployeeNo();
        User user = User.createForHire(dept, wg, employeeNo, asString(p.get("name")), email,
                asRole(p.get("role")), asString(p.get("position")), asDate(p.get("hire_date")));
        User saved = userRepository.save(user);
        // 임시 비밀번호 = 사번 (UC-HR-02 A2: 첫 로그인 시 변경 강제 — 변경 화면은 Phase 5)
        credentialRepository.save(kr.onwork.common.domain.UserCredential.create(
                saved.getId(), passwordEncoder.encode(employeeNo)));
        return saved;
    }

    // ---------------------------------------------------------------- 직원 조회 (역할별 범위)
    @Transactional(readOnly = true)
    public List<EmployeeResponse> listEmployees(AuthPrincipal principal, Long departmentId,
                                                UserStatus status, String keyword) {
        Role role = principal.role();
        if (role == Role.EMPLOYEE) {
            return userRepository.findById(principal.userId())
                    .map(u -> List.of(EmployeeResponse.from(u))).orElse(List.of());
        }
        Long scopedDept = departmentId;
        if (role == Role.MANAGER) {
            User me = userRepository.findById(principal.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            scopedDept = me.getDepartment() != null ? me.getDepartment().getId() : -1L; // 본인 팀만
        }
        return userRepository.search(scopedDept, status, emptyToNull(keyword)).stream()
                .map(EmployeeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getEmployee(AuthPrincipal principal, Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Role role = principal.role();
        boolean self = principal.userId().equals(id);
        if (self || isExecutive(role) || role == Role.HR_MANAGER) {
            return EmployeeResponse.from(user);
        }
        if (role == Role.MANAGER) {
            User me = userRepository.findById(principal.userId()).orElse(null);
            Long myDept = (me != null && me.getDepartment() != null) ? me.getDepartment().getId() : null;
            Long targetDept = user.getDepartment() != null ? user.getDepartment().getId() : null;
            if (myDept != null && myDept.equals(targetDept)) {
                return EmployeeResponse.from(user);
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "해당 직원 정보 조회 권한이 없습니다");
    }

    // ---------------------------------------------------------------- helpers
    /** 알림 발송 격리 — UC-HR-01 E4: 변경 요청 저장은 완료, 알림 실패 시 로깅(재시도 큐는 v2). */
    private void notifyApprovers(HrChangeRequest request) {
        try {
            List<User> approvers = userRepository.findByRoleInAndStatus(
                    List.of(Role.CEO, Role.VP), UserStatus.ACTIVE);
            for (User approver : approvers) {
                notificationService.notify(approver.getId(), NotificationService.HR_CHANGE_REQUESTED,
                        "HR", request.getId(),
                        "새 인사 변경 요청(" + request.getChangeType().name() + ")이 승인 대기 중입니다");
            }
        } catch (RuntimeException e) {
            log.warn("[HR notify] 알림 발송 실패(저장은 완료): requestId={} reason={}",
                    request.getId(), e.getMessage());
        }
    }

    private void history(Long targetUserId, ChangeType type, Map<String, Object> before,
                         Map<String, Object> after, Long requestId, Long changedBy) {
        historyRepository.save(EmployeeChangeHistory.record(targetUserId, type, before, after, requestId, changedBy));
    }

    private Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole().name());
        m.put("position", u.getPosition());
        m.put("status", u.getStatus().name());
        m.put("department_id", u.getDepartment() != null ? u.getDepartment().getId() : null);
        return m;
    }

    private Long departmentIdOf(Long userId) {
        return userRepository.findById(userId)
                .map(User::getDepartment)
                .map(Department::getId)
                .orElse(null);
    }

    private Department resolveDepartment(Object idObj) {
        Long id = asLong(idObj);
        if (id == null) {
            return null;   // 미분류 허용 (UC-HR-01 A2)
        }
        return departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PAYLOAD, "부서를 찾을 수 없습니다"));
    }

    private long workGroupId(Map<String, Object> p) {
        Long id = asLong(p.get("work_group_id"));
        return id != null ? id : DEFAULT_WORK_GROUP_ID;
    }

    /** UC-HR-01 E1: 자동 채번 + 중복 시 다음 가용 번호로 재채번. */
    private String generateEmployeeNo() {
        int year = LocalDate.now().getYear();
        long seq = userRepository.count() + 1;
        String candidate;
        while (userRepository.existsByEmployeeNo(candidate = "%d-%03d".formatted(year, seq))) {
            seq++;
        }
        return candidate;
    }

    private boolean isExecutive(Role role) {
        return role == Role.CEO || role == Role.VP;
    }

    private void requireField(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_PAYLOAD,
                    ErrorCode.INVALID_PAYLOAD.defaultMessage() + " (필드: " + key + ")");
        }
    }

    private String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate asDate(Object o) {
        if (o == null) return null;
        try {
            return LocalDate.parse(o.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Role asRole(Object o) {
        if (o == null) return null;
        try {
            return Role.valueOf(o.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
