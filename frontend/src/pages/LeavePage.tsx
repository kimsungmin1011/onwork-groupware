import { useCallback, useEffect, useState, type FormEvent } from 'react'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'

interface Balance {
  leaveTypeCode: string
  leaveTypeName: string
  totalDays: number
  usedDays: number
  remainingDays: number
}
interface LeaveReq {
  id: number
  userName: string
  startDate: string
  endDate: string
  daysUsed: number
  status: string
  holdReason: string | null
  delegated: boolean
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '대기', APPROVED: '승인', ON_HOLD: '보류', CANCELLED: '취소',
}
const LEAVE_TYPES = [
  { id: 1, label: '연차' }, { id: 2, label: '보상휴가' },
  { id: 3, label: '오전반차' }, { id: 4, label: '오후반차' },
]

export default function LeavePage() {
  const { user } = useAuth()
  const canApprove = user ? ['CEO', 'VP', 'HR_MANAGER', 'MANAGER'].includes(user.role) : false

  const [balances, setBalances] = useState<Balance[]>([])
  const [mine, setMine] = useState<LeaveReq[]>([])
  const [inbox, setInbox] = useState<LeaveReq[]>([])
  const [form, setForm] = useState({ leaveTypeId: 1, startDate: '', endDate: '', reason: '' })
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const [b, m] = await Promise.all([
      api.get<{ items: Balance[] }>('/leave/balances'),
      api.get<{ items: LeaveReq[] }>('/leave/requests'),
    ])
    setBalances(b.data.items)
    setMine(m.data.items)
    if (canApprove) {
      const i = await api.get<{ items: LeaveReq[] }>('/leave/inbox')
      setInbox(i.data.items)
    }
  }, [canApprove])

  useEffect(() => { load() }, [load])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    try {
      const isHalf = form.leaveTypeId === 3 || form.leaveTypeId === 4
      await api.post('/leave/requests', {
        leaveTypeId: form.leaveTypeId,
        startDate: form.startDate,
        endDate: isHalf ? form.startDate : form.endDate,
        reason: form.reason,
      })
      setForm({ leaveTypeId: 1, startDate: '', endDate: '', reason: '' })
      await load()
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? '신청 실패')
    }
  }

  async function process(id: number, action: 'APPROVE' | 'ON_HOLD') {
    let reason: string | null = null
    if (action === 'ON_HOLD') {
      reason = window.prompt('보류 사유를 입력하세요')
      if (!reason) return
    }
    await api.patch(`/leave/requests/${id}/process`, { action, reason })
    await load()
  }

  async function cancel(id: number) {
    await api.patch(`/leave/requests/${id}/cancel`)
    await load()
  }

  const annual = balances.find((b) => b.leaveTypeCode === 'ANNUAL')

  return (
    <AppLayout>
      <h1 className="page-title">휴가관리</h1>

      <section className="card-block">
        <h2 className="section-title">내 휴가 잔여</h2>
        {annual && (
          <div className="clock-row">
            <div className="clock-stat"><span className="muted">연차 총</span><strong>{annual.totalDays}</strong></div>
            <div className="clock-stat"><span className="muted">사용</span><strong>{annual.usedDays}</strong></div>
            <div className="clock-stat"><span className="muted">잔여</span><strong>{annual.remainingDays}</strong></div>
          </div>
        )}
      </section>

      <section className="card-block">
        <h2 className="section-title">휴가 신청</h2>
        <form className="leave-form" onSubmit={submit}>
          <select value={form.leaveTypeId} onChange={(e) => setForm({ ...form, leaveTypeId: Number(e.target.value) })}>
            {LEAVE_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
          <input type="date" value={form.startDate} required onChange={(e) => setForm({ ...form, startDate: e.target.value })} />
          <input type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })}
                 disabled={form.leaveTypeId === 3 || form.leaveTypeId === 4} placeholder="종료일" />
          <input type="text" value={form.reason} placeholder="사유" onChange={(e) => setForm({ ...form, reason: e.target.value })} />
          <button className="btn-primary" type="submit">신청</button>
        </form>
        {error && <p className="auth-error">{error}</p>}
      </section>

      {canApprove && (
        <section className="card-block" data-testid="leave-inbox">
          <h2 className="section-title">휴가 결재함 (대기 {inbox.length}건)</h2>
          {inbox.length === 0 ? <p className="muted">대기 중인 휴가 신청이 없습니다.</p> : (
            <table className="data-table">
              <thead><tr><th>신청자</th><th>기간</th><th>일수</th><th>처리</th></tr></thead>
              <tbody>
                {inbox.map((r) => (
                  <tr key={r.id}>
                    <td>{r.userName}</td>
                    <td>{r.startDate} ~ {r.endDate}</td>
                    <td>{r.daysUsed}</td>
                    <td className="actions">
                      <button className="btn-sm primary" onClick={() => process(r.id, 'APPROVE')}>승인</button>
                      <button className="btn-sm" onClick={() => process(r.id, 'ON_HOLD')}>보류</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      <section className="card-block">
        <h2 className="section-title">내 휴가 신청 내역</h2>
        {mine.length === 0 ? <p className="muted">신청 내역이 없습니다.</p> : (
          <table className="data-table">
            <thead><tr><th>기간</th><th>일수</th><th>상태</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.startDate} ~ {r.endDate}</td>
                  <td>{r.daysUsed}</td>
                  <td>
                    <span className={'badge ' + (r.status === 'APPROVED' ? 'active' : r.status === 'CANCELLED' ? 'resigned' : 'inactive')}>
                      {STATUS_LABEL[r.status] ?? r.status}{r.delegated ? ' (대행)' : ''}
                    </span>
                  </td>
                  <td>
                    {(r.status === 'PENDING' || r.status === 'APPROVED') && (
                      <button className="btn-sm" onClick={() => cancel(r.id)}>취소</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </AppLayout>
  )
}
