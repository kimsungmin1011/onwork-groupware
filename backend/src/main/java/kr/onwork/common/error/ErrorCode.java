package kr.onwork.common.error;

import org.springframework.http.HttpStatus;

/** API 에러 코드 — 응답 형식 {code, message} (API 명세 공통 규칙). */
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "로그인 5회 실패로 30분간 잠금되었습니다"),
    ACCOUNT_INACTIVE(HttpStatus.UNAUTHORIZED, "활성화되지 않은 계정입니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),

    // 공통 자원
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "필수 정보가 누락되었습니다"),
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "이미 등록된 이메일입니다"),

    // HR
    INVALID_RESIGN_DATE(HttpStatus.BAD_REQUEST, "퇴사일은 오늘 이후 날짜여야 합니다"),
    ALREADY_PROCESSED(HttpStatus.BAD_REQUEST, "이미 처리된 요청입니다"),
    CANNOT_SELF_APPROVE(HttpStatus.BAD_REQUEST, "본인이 요청한 건은 직접 승인할 수 없습니다"),

    // Leave
    LEAVE_INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔여 휴가일수가 부족합니다"),
    LEAVE_OVERLAP(HttpStatus.BAD_REQUEST, "이미 신청한 기간과 중복됩니다"),
    LEAVE_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "휴가 기간이 올바르지 않습니다"),
    LEAVE_BALANCE_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 휴가 잔여 정보가 없습니다"),

    // 공통
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
