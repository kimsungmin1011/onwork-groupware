package kr.onwork.notification.service;

import kr.onwork.notification.domain.Notification;
import kr.onwork.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 알림 생성/조회 공통 서비스 — 모든 모듈이 재사용. */
@Service
public class NotificationService {

    // 알림 유형 코드 (notifications.type — 데이터사전)
    public static final String HR_CHANGE_REQUESTED = "HR_CHANGE_REQUESTED";
    public static final String HR_CHANGE_APPROVED = "HR_CHANGE_APPROVED";
    public static final String HR_CHANGE_REJECTED = "HR_CHANGE_REJECTED";
    public static final String LEAVE_REQUESTED = "LEAVE_REQUESTED";
    public static final String LEAVE_APPROVED = "LEAVE_APPROVED";
    public static final String LEAVE_ON_HOLD = "LEAVE_ON_HOLD";

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void notify(Long userId, String type, String refType, Long refId, String message) {
        repository.save(Notification.create(userId, type, refType, refId, message));
    }
}
