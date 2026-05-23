package kr.onwork.attendance.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.onwork.attendance.domain.AnomalyType;
import kr.onwork.attendance.domain.AttendanceSetting;
import kr.onwork.attendance.domain.DailyWorkRecord;
import kr.onwork.attendance.domain.OvertimeRequest;
import kr.onwork.attendance.domain.OvertimeStatus;
import kr.onwork.attendance.domain.WorkAnomaly;
import kr.onwork.attendance.dto.AnomalyResponse;
import kr.onwork.attendance.dto.AttendanceProcessRequest;
import kr.onwork.attendance.dto.ClockResponse;
import kr.onwork.attendance.dto.OvertimeCreateRequest;
import kr.onwork.attendance.dto.OvertimeResponse;
import kr.onwork.attendance.repository.AttendanceSettingRepository;
import kr.onwork.attendance.repository.DailyWorkRecordRepository;
import kr.onwork.attendance.repository.OvertimeRequestRepository;
import kr.onwork.attendance.repository.WorkAnomalyRepository;
import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.domain.UserStatus;
import kr.onwork.common.error.BusinessException;
import kr.onwork.common.error.ErrorCode;
import kr.onwork.common.repository.UserRepository;
import kr.onwork.common.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근태 비즈니스 로직. 출퇴근은 서버 시각 기준(UC-ATTENDANCE-01), 당일 1회.
 * 결근 자동 감지는 자정 배치(ADR-ATT-001, AttendanceScheduler가 호출).
 */
@Service
public class AttendanceService {

    private final DailyWorkRecordRepository recordRepository;
    private final WorkAnomalyRepository anomalyRepository;
    private final OvertimeRequestRepository overtimeRepository;
    private final AttendanceSettingRepository settingRepository;
    private final UserRepository userRepository;

    public AttendanceService(DailyWorkRecordRepository recordRepository,
                             WorkAnomalyRepository anomalyRepository,
                             OvertimeRequestRepository overtimeRepository,
                             AttendanceSettingRepository settingRepository,
                             UserRepository userRepository) {
        this.recordRepository = recordRepository;
        this.anomalyRepository = anomalyRepository;
        this.overtimeRepository = overtimeRepository;
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
    }

    // ---------------------------------------------------------------- 출근
    @Transactional
    public ClockResponse clockIn(AuthPrincipal principal) {
        User user = loadUser(principal.userId());
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        DailyWorkRecord record = recordRepository.findByUserIdAndDate(user.getId(), today)
                .orElseGet(() -> DailyWorkRecord.of(user.getId(), today));
        if (record.hasClockIn()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 출근 처리되었습니다");
        }
        LocalTime start = user.getWorkGroup().getWorkStartTime();
        LocalTime lateLine = start.plusMinutes(setting().getGraceInMinutes());
        boolean late = now.toLocalTime().isAfter(lateLine);
        record.clockIn(now, late);
        DailyWorkRecord saved = recordRepository.save(record);
        if (late) {
            anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.LATE));
        }
        return ClockResponse.from(saved, late, false);
    }

    // ---------------------------------------------------------------- 퇴근
    @Transactional
    public ClockResponse clockOut(AuthPrincipal principal) {
        User user = loadUser(principal.userId());
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        DailyWorkRecord record = recordRepository.findByUserIdAndDate(user.getId(), today)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "출근 기록이 없습니다"));
        if (!record.hasClockIn()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "출근 기록이 없습니다");
        }
        if (record.hasClockOut()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 퇴근 처리되었습니다");
        }
        LocalTime end = user.getWorkGroup().getWorkEndTime();
        LocalTime earlyLine = end.minusMinutes(setting().getGraceOutMinutes());
        boolean early = now.toLocalTime().isBefore(earlyLine);
        int overtime = now.toLocalTime().isAfter(end)
                ? (int) Duration.between(end, now.toLocalTime()).toMinutes() : 0;
        record.clockOut(now, overtime, early);
        DailyWorkRecord saved = recordRepository.save(record);
        if (early) {
            anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.EARLY_LEAVE));
        }
        return ClockResponse.from(saved, false, early);
    }

    @Transactional(readOnly = true)
    public ClockResponse today(AuthPrincipal principal) {
        LocalDate today = LocalDate.now();
        return recordRepository.findByUserIdAndDate(principal.userId(), today)
                .map(r -> ClockResponse.from(r, false, false))
                .orElse(new ClockResponse(null, today, null, null, 0, "NONE", false, false));
    }

    // ---------------------------------------------------------------- 결근 배치 (ADR-ATT-001)
    /** 지정일에 출근 기록이 없는 재직자에게 ABSENT, 퇴근 누락자에게 CLOCK_MISSING 이상 자동 생성. */
    @Transactional
    public int detectAbsences(LocalDate date) {
        List<User> active = userRepository.search(null, UserStatus.ACTIVE, null);
        int created = 0;
        for (User u : active) {
            DailyWorkRecord record = recordRepository.findByUserIdAndDate(u.getId(), date).orElse(null);
            if (record == null) {
                DailyWorkRecord absent = DailyWorkRecord.of(u.getId(), date);
                absent.markAnomaly();
                DailyWorkRecord saved = recordRepository.save(absent);
                anomalyRepository.save(WorkAnomaly.of(saved.getId(), AnomalyType.ABSENT));
                created++;
            } else if (record.hasClockIn() && !record.hasClockOut()
                    && !anomalyRepository.existsByDailyWorkRecordIdAndAnomalyType(
                            record.getId(), AnomalyType.CLOCK_MISSING)) {
                record.markAnomaly();
                recordRepository.save(record);
                anomalyRepository.save(WorkAnomaly.of(record.getId(), AnomalyType.CLOCK_MISSING));
                created++;
            }
        }
        return created;
    }

    // ---------------------------------------------------------------- 팀 근태 이상 (UC-ATTENDANCE-02)
    @Transactional(readOnly = true)
    public List<AnomalyResponse> listAnomalies(AuthPrincipal principal, LocalDate date) {
        List<User> scope = scopedUsers(principal);
        Map<Long, String> nameById = scope.stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        List<DailyWorkRecord> records = recordRepository.findByUserIdInAndDate(
                scope.stream().map(User::getId).toList(), date);
        Map<Long, DailyWorkRecord> recordById = records.stream()
                .collect(Collectors.toMap(DailyWorkRecord::getId, Function.identity()));
        if (records.isEmpty()) {
            return List.of();
        }
        return anomalyRepository.findByDailyWorkRecordIdIn(records.stream().map(DailyWorkRecord::getId).toList())
                .stream()
                .map(a -> {
                    DailyWorkRecord rec = recordById.get(a.getDailyWorkRecordId());
                    return new AnomalyResponse(a.getId(), rec.getUserId(),
                            nameById.getOrDefault(rec.getUserId(), "?"),
                            rec.getDate(), a.getAnomalyType().name(), a.isConfirmed());
                })
                .toList();
    }

    @Transactional
    public void confirmAnomaly(AuthPrincipal principal, Long anomalyId) {
        WorkAnomaly anomaly = anomalyRepository.findById(anomalyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (anomaly.isConfirmed()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED, "이미 확인된 이상 항목입니다");
        }
        DailyWorkRecord record = recordRepository.findById(anomaly.getDailyWorkRecordId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireManageable(principal, record.getUserId());
        anomaly.confirm(principal.userId());
    }

    // ---------------------------------------------------------------- 시간외근로 (UC-03/04)
    @Transactional
    public OvertimeResponse requestOvertime(AuthPrincipal principal, OvertimeCreateRequest req) {
        if (!req.expectedEndAt().isAfter(req.expectedStartAt())) {
            throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "종료 시각은 시작 시각 이후여야 합니다");
        }
        OvertimeRequest saved = overtimeRepository.save(OvertimeRequest.create(
                principal.userId(), req.requestDate(), req.expectedStartAt(), req.expectedEndAt(), req.reason()));
        return OvertimeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<OvertimeResponse> myOvertime(AuthPrincipal principal) {
        return overtimeRepository.findByUserIdOrderByIdDesc(principal.userId())
                .stream().map(OvertimeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<OvertimeResponse> overtimeInbox(AuthPrincipal principal) {
        List<Long> ids = scopedUsers(principal).stream().map(User::getId).toList();
        return overtimeRepository.findByUserIdInAndStatusOrderByIdDesc(ids, OvertimeStatus.PENDING)
                .stream().map(OvertimeResponse::from).toList();
    }

    @Transactional
    public OvertimeResponse processOvertime(AuthPrincipal principal, Long id, AttendanceProcessRequest req) {
        OvertimeRequest request = overtimeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED);
        }
        if (request.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.CANNOT_SELF_APPROVE);
        }
        requireManageable(principal, request.getUserId());
        if (req.action() == AttendanceProcessRequest.Action.REJECT) {
            if (req.reason() == null || req.reason().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PAYLOAD, "반려 사유는 필수입니다");
            }
            request.reject(principal.userId(), req.reason());
        } else {
            request.approve(principal.userId());
        }
        return OvertimeResponse.from(request);
    }

    // ---------------------------------------------------------------- helpers
    private AttendanceSetting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "근태 설정이 없습니다"));
    }

    private User loadUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 역할별 조회 범위: 경영진/HR=전체, 팀장=본인 부서, 사원=본인. */
    private List<User> scopedUsers(AuthPrincipal principal) {
        Role role = principal.role();
        if (role == Role.CEO || role == Role.VP || role == Role.HR_MANAGER) {
            return userRepository.search(null, null, null);
        }
        if (role == Role.MANAGER) {
            User me = loadUser(principal.userId());
            Long deptId = me.getDepartment() != null ? me.getDepartment().getId() : -1L;
            return userRepository.search(deptId, null, null);
        }
        return List.of(loadUser(principal.userId()));
    }

    private void requireManageable(AuthPrincipal principal, Long targetUserId) {
        Role role = principal.role();
        if (role == Role.CEO || role == Role.VP || role == Role.HR_MANAGER) {
            return;
        }
        if (role == Role.MANAGER) {
            User me = loadUser(principal.userId());
            User target = loadUser(targetUserId);
            Long myDept = me.getDepartment() != null ? me.getDepartment().getId() : null;
            Long targetDept = target.getDepartment() != null ? target.getDepartment().getId() : null;
            if (myDept != null && myDept.equals(targetDept)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "해당 직원 근태를 처리할 권한이 없습니다");
    }
}
