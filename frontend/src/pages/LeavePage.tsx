import { useCallback, useEffect, useState, type FormEvent } from 'react'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { isApprover, isExecutive } from '../lib/roles'

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
interface Employee {
  id: number
  name: string
  departmentName?: string | null
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '대기', APPROVED: '승인', ON_HOLD: '보류', CANCELLED: '취소',
}
const LEAVE_TYPES = [
  { id: 1, label: '연차' }, { id: 2, label: '보상휴가' },
  { id: 3, label: '오전반차' }, { id: 4, label: '오후반차' },
]

function balanceUsePercent(balance?: Balance) {
  if (!balance || balance.totalDays <= 0) return 0
  return Math.min(100, Math.round((Number(balance.usedDays) / Number(balance.totalDays)) * 100))
}

export default function LeavePage() {
  const { user } = useAuth()
  const canApprove = isApprover(user?.role)
  const isExec = isExecutive(user?.role)   // 경영진(CEO/VP)만 보상휴가 부여

  const [balances, setBalances] = useState<Balance[]>([])
  const [mine, setMine] = useState<LeaveReq[]>([])
  const [inbox, setInbox] = useState<LeaveReq[]>([])
  const [detail, setDetail] = useState<LeaveReq | null>(null)
  const [form, setForm] = useState({ leaveTypeId: 1, startDate: '', endDate: '', reason: '' })
  const [error, setError] = useState('')
  const [employees, setEmployees] = useState<Employee[]>([])
  const [grantTargets, setGrantTargets] = useState<Set<number>>(new Set())
  const [grantForm, setGrantForm] = useState({ days: '1', reason: '' })
  const [grantMsg, setGrantMsg] = useState<string | null>(null)

  const load = useCallback(async () => {
    const [b, m] = await Promise.all([
      api.get<{ items: Balance[] }>('/leave-balances/me'),
      api.get<{ items: LeaveReq[] }>('/leave-requests/me'),
    ])
    setBalances(b.data.items)
    setMine(m.data.items)
    if (canApprove) {
      const i = await api.get<{ items: LeaveReq[] }>('/leave/inbox')
      setInbox(i.data.items)
    }
    if (isExec) {
      const e = await api.get<{ items: Employee[] }>('/hr/employees')
      setEmployees(e.data.items)
    }
  }, [canApprove, isExec])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError('')
    try {
      const isHalf = form.leaveTypeId === 3 || form.leaveTypeId === 4
      await api.post('/leave-requests', {
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
    await api.patch(`/leave-requests/${id}/process`, { action, reason })
    setDetail(null)
    await load()
  }

  async function cancel(id: number) {
    await api.patch(`/leave-requests/${id}/cancel`)
    await load()
  }

  // 대상 직원 다중/팀 선택 토글
  function toggleGrantUser(id: number) {
    setGrantTargets((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  function toggleGrantTeam(team: string) {
    const ids = employees.filter((emp) => (emp.departmentName ?? '미분류') === team).map((emp) => emp.id)
    setGrantTargets((prev) => {
      const next = new Set(prev)
      const allOn = ids.every((id) => next.has(id))
      ids.forEach((id) => (allOn ? next.delete(id) : next.add(id)))
      return next
    })
  }
  function toggleGrantAll() {
    setGrantTargets((prev) => (prev.size === employees.length ? new Set() : new Set(employees.map((e) => e.id))))
  }

  // 경영진 보상휴가 부여 (UC-LEAVE-03, POST /leave-grants, VP+). 다중·팀 단위 부여 가능.
  async function submitGrant(e: FormEvent) {
    e.preventDefault()
    setGrantMsg(null)
    const days = Number(grantForm.days)
    const ids = [...grantTargets]
    if (ids.length === 0 || !(days >= 0.5)) {
      setGrantMsg('대상 직원을 1명 이상 선택하고 0.5일 이상을 입력하세요')
      return
    }
    try {
      const { data } = await api.post<{ successCount: number; failureCount: number; total: number }>('/leave-grants', {
        userIds: ids,
        days,
        reason: grantForm.reason || null,
      })
      setGrantMsg(`보상휴가 ${days}일 부여 — 성공 ${data.successCount}명${data.failureCount ? ` / 실패 ${data.failureCount}명` : ''} (총 ${data.total}명)`)
      setGrantTargets(new Set())
      setGrantForm({ days: '1', reason: '' })
      await load()
    } catch (err) {
      setGrantMsg((err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? '부여 실패')
    }
  }

  const annual = balances.find((b) => b.leaveTypeCode === 'ANNUAL')
  const comp = balances.find((b) => b.leaveTypeCode === 'COMP')
  const selectedLeaveType = LEAVE_TYPES.find((t) => t.id === form.leaveTypeId)
  const isHalfDay = form.leaveTypeId === 3 || form.leaveTypeId === 4
  const estimateDays = estimateLeaveDays(form.leaveTypeId, form.startDate, isHalfDay ? form.startDate : form.endDate)
  const sourceBalance = form.leaveTypeId === 2 ? comp : annual
  const afterRemaining = sourceBalance ? Math.max(Number(sourceBalance.remainingDays) - estimateDays, 0) : 0
  const annualUsePercent = balanceUsePercent(annual)
  const compUsePercent = balanceUsePercent(comp)
  const pendingMine = mine.filter((item) => item.status === 'PENDING' || item.status === 'ON_HOLD').length
  const approvedMine = mine.filter((item) => item.status === 'APPROVED').length
  const grantTeams = [...new Set(employees.map((e) => e.departmentName ?? '미분류'))]

  return (
    <AppLayout>
      <div className="work-screen">
        <section className="screen-hero leave-hero" data-testid="leave-request-form">
          <div>
            <p className="screen-kicker">{canApprove ? '휴가 운영' : '휴가 셀프서비스'}</p>
            <h1>{canApprove ? '휴가관리' : '내 휴가'}</h1>
            <p>잔여일, 신청, 결재 상태를 한 화면에서 확인하고 휴가 흐름을 빠르게 처리합니다.</p>
          </div>
          <div className="leave-hero-meter">
            <span>연차 잔여</span>
            <strong>{annual ? `${annual.remainingDays}일` : '-'}</strong>
            <div className="today-progress"><span style={{ width: `${annualUsePercent}%` }} /></div>
            <small>총 {annual?.totalDays ?? 0}일 중 {annual?.usedDays ?? 0}일 사용</small>
          </div>
        </section>

        <section className="metric-grid">
          <article className="metric-card tone-green">
            <span className="metric-label">연차 잔여</span>
            <strong className="metric-value">{annual ? `${annual.remainingDays}일` : '-'}</strong>
            <span className="metric-hint">사용률 {annualUsePercent}%</span>
          </article>
          <article className="metric-card tone-blue">
            <span className="metric-label">연차 사용</span>
            <strong className="metric-value">{annual?.usedDays ?? '-'}</strong>
            <span className="metric-hint">총 {annual?.totalDays ?? 0}일</span>
          </article>
          <article className="metric-card tone-amber">
            <span className="metric-label">보상휴가</span>
            <strong className="metric-value">{comp ? `${comp.remainingDays}일` : '0일'}</strong>
            <span className="metric-hint">사용률 {compUsePercent}%</span>
          </article>
          <article className="metric-card tone-rose">
            <span className="metric-label">진행 중 신청</span>
            <strong className="metric-value">{pendingMine}</strong>
            <span className="metric-hint">승인 이력 {approvedMine}건</span>
          </article>
          {canApprove && (
            <article className="metric-card tone-blue">
              <span className="metric-label">결재 대기</span>
              <strong className="metric-value">{inbox.length}</strong>
              <span className="metric-hint">휴가 신청 검토</span>
            </article>
          )}
        </section>

        <section className="service-grid two-column">
          <div className="card-block service-panel">
            <div className="panel-heading">
              <div>
                <h2>휴가 신청</h2>
                <p>입력 중 예상 차감일과 신청 후 잔여일을 바로 확인합니다.</p>
              </div>
            </div>
            <form className="pro-form" onSubmit={submit}>
              <select value={form.leaveTypeId} onChange={(e) => setForm({ ...form, leaveTypeId: Number(e.target.value) })}>
                {LEAVE_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
              </select>
              <input type="date" value={form.startDate} required onChange={(e) => setForm({ ...form, startDate: e.target.value })}
                     title={isHalfDay ? '반차 신청일' : '시작일'} />
              <input type="date" value={isHalfDay ? form.startDate : form.endDate}
                     onChange={(e) => setForm({ ...form, endDate: e.target.value })}
                     required={!isHalfDay} disabled={isHalfDay}
                     title={isHalfDay ? '반차는 당일만 신청합니다' : '종료일'} placeholder="종료일" />
              <input type="text" value={form.reason} placeholder="사유" onChange={(e) => setForm({ ...form, reason: e.target.value })} />
              <button className="btn-primary" type="submit">신청</button>
            </form>
            <div className="form-insight">
              <span>{selectedLeaveType?.label ?? '휴가'} 예상 차감 {estimateDays}일</span>
              <span>현재 잔여 {sourceBalance?.remainingDays ?? 0}일</span>
              <span>신청 후 잔여 {afterRemaining}일</span>
              {form.leaveTypeId === 2 && <span>보상휴가 소멸일은 부여 이력 기준으로 관리됩니다.</span>}
            </div>
            {error && <p className="auth-error">{error}</p>}
          </div>

          <div className="card-block service-panel">
            <div className="panel-heading">
              <div>
                <h2>잔여 구성</h2>
                <p>연차와 보상휴가 사용률을 구분해 보여줍니다.</p>
              </div>
            </div>
            <div className="balance-bars">
              {[annual, comp].filter(Boolean).map((balance) => (
                <div key={balance!.leaveTypeCode}>
                  <div><strong>{balance!.leaveTypeName}</strong><span>{balance!.remainingDays}일 남음</span></div>
                  <div className="today-progress"><span style={{ width: `${balanceUsePercent(balance) || 3}%` }} /></div>
                </div>
              ))}
            </div>
          </div>
        </section>

      {isExec && (
        <section className="card-block service-panel" data-testid="comp-grant">
          <div className="panel-heading">
            <div>
              <h2>보상휴가 부여</h2>
              <p>경영진 전용 — 여러 직원 또는 팀 단위로 보상휴가를 부여합니다(결재 불필요).</p>
            </div>
            <span className="count-chip">선택 {grantTargets.size}명</span>
          </div>
          <form className="pro-form" onSubmit={submitGrant}>
            <div style={{ gridColumn: '1 / -1' }}>
              <span className="field" style={{ marginBottom: 6, display: 'block' }}>대상 직원 (팀 단위 선택 가능)</span>
              <div className="grant-teams">
                <button type="button" className={`grant-team-btn${grantTargets.size === employees.length && employees.length > 0 ? ' on' : ''}`} onClick={toggleGrantAll}>전체</button>
                {grantTeams.map((team) => {
                  const ids = employees.filter((emp) => (emp.departmentName ?? '미분류') === team).map((emp) => emp.id)
                  const allOn = ids.length > 0 && ids.every((id) => grantTargets.has(id))
                  return (
                    <button key={team} type="button" className={`grant-team-btn${allOn ? ' on' : ''}`} onClick={() => toggleGrantTeam(team)}>
                      {team}
                    </button>
                  )
                })}
              </div>
              <div className="grant-checklist">
                {employees.map((emp) => (
                  <label key={emp.id} className={grantTargets.has(emp.id) ? 'on' : ''}>
                    <input type="checkbox" checked={grantTargets.has(emp.id)} onChange={() => toggleGrantUser(emp.id)} />
                    <span>{emp.name}</span>
                    <span className="dept">{emp.departmentName ?? '미분류'}</span>
                  </label>
                ))}
              </div>
            </div>
            <label className="field">
              <span>부여 일수</span>
              <input type="number" min="0.5" step="0.5" required value={grantForm.days}
                     onChange={(e) => setGrantForm({ ...grantForm, days: e.target.value })} />
            </label>
            <label className="field">
              <span>사유 (선택)</span>
              <input type="text" placeholder="예: 휴일 근무 보상" value={grantForm.reason}
                     onChange={(e) => setGrantForm({ ...grantForm, reason: e.target.value })} />
            </label>
            <button className="btn-primary" type="submit" style={{ gridColumn: '1 / -1' }} disabled={grantTargets.size === 0}>
              {grantTargets.size > 0 ? `${grantTargets.size}명에게 보상휴가 부여` : '보상휴가 부여'}
            </button>
          </form>
          {grantMsg && <p className="toast-line">{grantMsg}</p>}
        </section>
      )}

      {canApprove && (
        <section className="card-block service-panel" data-testid="leave-inbox">
          <div className="panel-heading">
            <div>
              <h2>휴가 결재함</h2>
              <p>행을 클릭하면 상세 내용과 처리 후 영향을 확인합니다.</p>
            </div>
            <span className="count-chip">대기 {inbox.length}건</span>
          </div>
          {inbox.length === 0 ? <div className="empty-state">대기 중인 휴가 신청이 없습니다.</div> : (
            <div className="table-shell">
              <table className="data-table">
                <thead><tr><th>신청자</th><th>기간</th><th>일수</th><th>처리</th></tr></thead>
                <tbody>
                  {inbox.map((r) => (
                    <tr key={r.id} className="clickable-row" onClick={() => setDetail(r)}>
                      <td><strong>{r.userName}</strong></td>
                      <td>{r.startDate} ~ {r.endDate}</td>
                      <td>{r.daysUsed}</td>
                      <td className="actions" onClick={(event) => event.stopPropagation()}>
                        <button className="btn-sm primary" onClick={() => process(r.id, 'APPROVE')}>승인</button>
                        <button className="btn-sm" onClick={() => process(r.id, 'ON_HOLD')}>보류</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      <section className="card-block service-panel">
        <div className="panel-heading">
          <div>
            <h2>내 휴가 신청 내역</h2>
            <p>대기·보류 상태만 직접 취소할 수 있습니다.</p>
          </div>
        </div>
        {mine.length === 0 ? <div className="empty-state">신청 내역이 없습니다.</div> : (
          <div className="table-shell">
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
                      {(r.status === 'PENDING' || r.status === 'ON_HOLD') && (
                        <button className="btn-sm" onClick={() => cancel(r.id)}>취소</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      </div>

      {detail && (
        <div className="modal-backdrop" role="presentation" onClick={() => setDetail(null)}>
          <section className="detail-drawer" role="dialog" aria-modal="true" aria-label="휴가 신청 상세" onClick={(event) => event.stopPropagation()}>
            <div className="detail-header">
              <div>
                <p className="muted small">휴가 신청 상세</p>
                <h2 className="section-title">{detail.userName}</h2>
              </div>
              <button className="btn-ghost" onClick={() => setDetail(null)}>닫기</button>
            </div>
            <dl className="detail-list">
              <div><dt>기간</dt><dd>{detail.startDate} ~ {detail.endDate}</dd></div>
              <div><dt>차감 일수</dt><dd>{detail.daysUsed}일</dd></div>
              <div><dt>상태</dt><dd>{STATUS_LABEL[detail.status] ?? detail.status}</dd></div>
              <div><dt>대행 여부</dt><dd>{detail.delegated ? '대행 결재' : '일반 결재'}</dd></div>
              <div className="detail-wide"><dt>보류 사유</dt><dd>{detail.holdReason ?? '-'}</dd></div>
              <div className="detail-wide"><dt>처리 후 영향</dt><dd>승인 시 잔여 휴가가 차감되고, 보류 시 신청자에게 사유가 알림으로 전달됩니다.</dd></div>
            </dl>
            <div className="detail-actions">
              <button className="btn-sm primary" onClick={() => process(detail.id, 'APPROVE')}>승인</button>
              <button className="btn-sm" onClick={() => process(detail.id, 'ON_HOLD')}>보류</button>
            </div>
          </section>
        </div>
      )}
    </AppLayout>
  )
}

function estimateLeaveDays(typeId: number, startDate: string, endDate: string) {
  if (!startDate || !endDate) return 0
  if (typeId === 3 || typeId === 4) return 0.5
  const start = new Date(`${startDate}T00:00:00`)
  const end = new Date(`${endDate}T00:00:00`)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end < start) return 0
  let days = 0
  for (let cursor = new Date(start); cursor <= end; cursor.setDate(cursor.getDate() + 1)) {
    const day = cursor.getDay()
    if (day !== 0 && day !== 6) days += 1
  }
  return days
}
