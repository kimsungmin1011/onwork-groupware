package kr.onwork.common.dto;

/**
 * 결재자 정보 표현(휴가·시간외 공용).
 *
 * @param name       현재 결재자 또는 처리자 이름. 자동 승인/미지정 시 null.
 * @param label      직책 라벨(예: "영업팀 팀장", "경영지원팀 팀장", "대표이사", "시스템 자동승인").
 * @param delegated  원 결재자 부재로 대행/에스컬레이션되었는지 여부.
 * @param absentName 부재로 건너뛴 원 결재자 이름(대행일 때만). 없으면 null.
 */
public record ApproverView(
        String name,
        String label,
        boolean delegated,
        String absentName
) {
}
