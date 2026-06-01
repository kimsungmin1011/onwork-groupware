// 결재자 표현(휴가·시간외 공용). 백엔드 ApproverView와 1:1 대응.
export interface Approver {
  name: string | null
  label: string
  delegated: boolean
  absentName: string | null
}

export interface ApproverText {
  primary: string // "박지수 결재 대기" / "정수연 승인" / "자동 승인"
  secondary: string // "경영지원팀 팀장" / "정수연 부재 → 대행" / "시스템"
  tone: 'pending' | 'ok' | 'hold' | 'rej' | 'muted'
}

// 상태 + 결재자 → 화면 표기. CANCELLED 또는 결재자 없음이면 null(— 표시).
export function describeApprover(status: string, approver?: Approver | null): ApproverText | null {
  if (!approver) return null

  // 자동 승인(시스템) — 결재자 이름 없음
  if (status === 'APPROVED' && !approver.name) {
    return { primary: '자동 승인', secondary: '시스템', tone: 'ok' }
  }

  const who = approver.name ?? '미지정'
  const verb =
    status === 'PENDING' ? '결재 대기'
    : status === 'APPROVED' ? '승인'
    : status === 'ON_HOLD' ? '보류'
    : status === 'REJECTED' ? '반려'
    : ''
  const tone: ApproverText['tone'] =
    status === 'APPROVED' ? 'ok'
    : status === 'PENDING' ? 'pending'
    : status === 'ON_HOLD' ? 'hold'
    : status === 'REJECTED' ? 'rej'
    : 'muted'

  const secondary = approver.delegated
    ? (approver.absentName ? `${approver.absentName} 부재 → 대행` : `대행 · ${approver.label}`)
    : approver.label

  return { primary: `${who} ${verb}`.trim(), secondary, tone }
}
