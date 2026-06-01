package kr.onwork.common.service;

import kr.onwork.common.domain.Role;
import kr.onwork.common.domain.User;
import kr.onwork.common.dto.ApproverView;
import kr.onwork.common.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 결재자 표시용 {@link ApproverView} 생성기. 이름/직책 라벨 산정을 한 곳에 모아
 * 휴가·시간외 등 여러 도메인 서비스가 동일한 표현을 재사용한다.
 */
@Component
public class ApproverViewFactory {

    private final UserRepository userRepository;

    public ApproverViewFactory(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 시스템 자동 승인(결재자 없음). */
    public ApproverView system() {
        return new ApproverView(null, "시스템 자동승인", false, null);
    }

    /**
     * 결재자 ID 기준 표현 생성.
     *
     * @param approverId  현재 결재자 또는 처리자 ID(null이면 미지정).
     * @param delegated   대행/에스컬레이션 여부.
     * @param absentUserId 부재로 건너뛴 원 결재자 ID(없으면 null).
     */
    public ApproverView of(Long approverId, boolean delegated, Long absentUserId) {
        String absentName = absentUserId != null ? nameOf(absentUserId) : null;
        if (approverId == null) {
            return new ApproverView(null, "미지정", delegated, absentName);
        }
        User u = userRepository.findById(approverId).orElse(null);
        if (u == null) {
            return new ApproverView(null, "미지정", delegated, absentName);
        }
        return new ApproverView(u.getName(), labelOf(u), delegated, absentName);
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getName).orElse(null);
    }

    /** "부서명 직급" 형태. 부서 없으면 직급만, 직급 비면 역할명으로 대체. */
    private String labelOf(User u) {
        String dept = u.getDepartment() != null ? u.getDepartment().getName() : null;
        String pos = (u.getPosition() != null && !u.getPosition().isBlank())
                ? u.getPosition() : roleLabel(u.getRole());
        return dept != null ? dept + " " + pos : pos;
    }

    private String roleLabel(Role role) {
        return switch (role) {
            case CEO -> "대표이사";
            case VP -> "경영진";
            case HR_MANAGER -> "경영지원팀장";
            case MANAGER -> "팀장";
            case EMPLOYEE -> "사원";
        };
    }
}
