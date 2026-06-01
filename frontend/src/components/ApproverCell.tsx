import { describeApprover, type Approver } from '../lib/approver'

// 휴가·시간외 내역의 '승인' 셀. 누가 결재 대기/승인/반려했는지, 대행 여부를 함께 표기.
export default function ApproverCell({ status, approver }: { status: string; approver?: Approver | null }) {
  const text = describeApprover(status, approver)
  if (!text) return <span className="muted small">—</span>
  return (
    <div className="approver-cell">
      <span className={`approver-line tone-${text.tone}`}>
        {text.primary}
        {approver?.delegated && <span className="tag-delegate">대행</span>}
      </span>
      <small className="approver-sub">{text.secondary}</small>
    </div>
  )
}
