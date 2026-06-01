import { useCallback, useEffect, useState, type FormEvent } from 'react'
import AppLayout from '../components/AppLayout'
import ApproverCell from '../components/ApproverCell'
import { api } from '../lib/api'
import type { Approver } from '../lib/approver'
import { useAuth } from '../lib/auth'
import { isApprover } from '../lib/roles'

interface Today {
  date: string
  clockInAt: string | null
  clockOutAt: string | null
  status: string
}

interface Anomaly {
  id: number
  userName: string
  date: string
  anomalyType: string
  confirmed: boolean
}
interface OvertimeRequest {
  id: number
  userId: number
  userName?: string
  requestDate: string
  expectedStartAt: string
  expectedEndAt: string
  reason: string
  status: string
  rejectReason: string | null
  approver?: Approver | null
}
interface MonthlyCloseResult {
  closed: boolean
  requiresConfirmation?: boolean
  unconfirmedAnomalyCount?: number
  closedCount?: number
  totalOvertimeMinutes?: number
  message?: string
}

const ANOMALY_LABEL: Record<string, string> = {
  LATE: '지각', EARLY_LEAVE: '조퇴', ABSENT: '결근',
  CLOCK_MISSING: '퇴근누락', UNAPPROVED_OVERTIME: '미승인 시간외',
}
const ANOMALY_OPTIONS = Object.keys(ANOMALY_LABEL)
const STATUS_LABEL: Record<string, string> = { PENDING: '대기', APPROVED: '승인', REJECTED: '반려' }

function fmtTime(iso: string | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

function statusTone(status?: string) {
  if (status === 'ANOMALY') return 'danger'
  if (status === 'NORMAL') return 'success'
  return 'pending'
}

export default function AttendancePage() {
  const { user } = useAuth()
  const isManagerUp = isApprover(user?.role)

  const [today, setToday] = useState<Today | null>(null)
  const [anomalies, setAnomalies] = useState<Anomaly[]>([])
  const [overtimeMine, setOvertimeMine] = useState<OvertimeRequest[]>([])
  const [overtimeInbox, setOvertimeInbox] = useState<OvertimeRequest[]>([])
  const [confirmForms, setConfirmForms] = useState<Record<number, { anomalyType: string; overtimeApproved: boolean }>>({})
  const [overtimeForm, setOvertimeForm] = useState(() => ({
    date: new Date().toLocaleDateString('en-CA'),   // 오늘(YYYY-MM-DD) 기본값
    startTime: '18:00',
    endTime: '20:00',
    reason: '',
  }))
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7))
  const [monthlyResult, setMonthlyResult] = useState<MonthlyCloseResult | null>(null)
  const [now, setNow] = useState(() => new Date())
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const [t, myOt] = await Promise.all([
      api.get<Today>('/attendance/me'),
      api.get<{ items: OvertimeRequest[] }>('/attendance/overtime-requests'),
    ])
    setToday(t.data)
    setOvertimeMine(myOt.data.items)
    if (isManagerUp) {
      const [a, ot] = await Promise.all([
        api.get<{ items: Anomaly[] }>('/attendance/anomalies'),
        api.get<{ items: OvertimeRequest[] }>('/attendance/overtime-requests/inbox'),
      ])
      setAnomalies(a.data.items)
      setOvertimeInbox(ot.data.items)
    }
  }, [isManagerUp])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000)
    return () => window.clearInterval(timer)
  }, [])

  async function clock(kind: 'clock-in' | 'clock-out') {
    setBusy(true)
    try {
      if (kind === 'clock-out') await api.patch('/attendance/clock-out')
      else await api.post('/attendance/clock-in')
      await load()
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      alert(msg ?? '처리에 실패했습니다')
    } finally {
      setBusy(false)
    }
  }

  async function confirm(id: number) {
    const form = confirmForms[id]
    await api.patch(`/attendance/anomalies/${id}/confirm`, {
      anomalyType: form?.anomalyType,
      overtimeApproved: form?.overtimeApproved,
    })
    await load()
  }

  async function submitOvertime(e: FormEvent) {
    e.preventDefault()
    const { date, startTime, endTime, reason } = overtimeForm
    if (!date || !startTime || !endTime) return
    if (endTime <= startTime) {
      alert('종료 시간은 시작 시간보다 늦어야 합니다')
      return
    }
    // 날짜 1개 + 시작/종료 시간 → 백엔드가 받는 타임스탬프(date+time)로 합성
    await api.post('/attendance/overtime-requests', {
      requestDate: date,
      expectedStartAt: `${date}T${startTime}`,
      expectedEndAt: `${date}T${endTime}`,
      reason,
    })
    setOvertimeForm({ date, startTime: '18:00', endTime: '20:00', reason: '' })
    await load()
  }

  async function processOvertime(id: number, action: 'APPROVE' | 'REJECT') {
    let reason: string | null = null
    if (action === 'REJECT') {
      reason = window.prompt('시간외 반려 사유를 입력하세요')
      if (!reason) return
    }
    await api.patch(`/attendance/overtime-requests/${id}/process`, { action, reason })
    await load()
  }

  async function closeMonth(forceConfirm = false) {
    const [year, monthPart] = month.split('-').map(Number)
    const { data } = await api.post<MonthlyCloseResult>('/attendance/monthly-summaries', {
      year,
      month: monthPart,
      forceConfirm,
    })
    setMonthlyResult(data)
    if (data.requiresConfirmation) {
      const ok = window.confirm(`${data.unconfirmedAnomalyCount ?? 0}건의 미확정 이상이 있습니다. 그래도 마감하시겠습니까?`)
      if (ok) await closeMonth(true)
    }
  }

  const clockedIn = !!today?.clockInAt
  const clockedOut = !!today?.clockOutAt
  const nextAction = !clockedIn ? '출근 가능' : clockedOut ? '오늘 근태 기록 완료' : '퇴근 가능'
  const currentTime = now.toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' })
  const pendingOvertime = overtimeMine.filter((item) => item.status === 'PENDING').length
  const approvedOvertime = overtimeMine.filter((item) => item.status === 'APPROVED').length

  return (
    <AppLayout>
      <div className="work-screen">
        <section className="screen-hero attendance-hero" data-testid="attendance-today">
          <div>
            <p className="screen-kicker">{isManagerUp ? '팀 근태 운영' : '나의 근무 기록'}</p>
            <h1>{isManagerUp ? '근태관리' : '내 근태'}</h1>
            <p>Asia/Seoul 기준으로 출퇴근, 시간외 신청, 이상 확정 상태를 한 화면에서 확인합니다.</p>
            <div className="hero-actions">
              <button className="btn-primary" disabled={busy || clockedIn} onClick={() => clock('clock-in')}>출근 기록</button>
              <button className="btn-ghost" disabled={busy || !clockedIn || clockedOut} onClick={() => clock('clock-out')}>퇴근 기록</button>
            </div>
          </div>
          <div className="live-clock-card">
            <div className={`clock-face ${clockedIn && !clockedOut ? 'active' : ''}`}>
              <span>{currentTime}</span>
            </div>
            <strong>{nextAction}</strong>
            <small>{today?.date ?? '오늘'} · 서버 기준 시간</small>
          </div>
        </section>

        <section className="metric-grid">
          <article className={`metric-card tone-${statusTone(today?.status)}`}>
            <span className="metric-label">오늘 상태</span>
            <strong className="metric-value">{today?.status === 'ANOMALY' ? '이상' : clockedOut ? '완료' : clockedIn ? '근무 중' : '대기'}</strong>
            <span className="metric-hint">다음 액션: {nextAction}</span>
          </article>
          <article className="metric-card tone-blue">
            <span className="metric-label">출근</span>
            <strong className="metric-value">{fmtTime(today?.clockInAt ?? null)}</strong>
            <span className="metric-hint">오늘 기록</span>
          </article>
          <article className="metric-card tone-green">
            <span className="metric-label">퇴근</span>
            <strong className="metric-value">{fmtTime(today?.clockOutAt ?? null)}</strong>
            <span className="metric-hint">미기록 시 퇴근 버튼 활성</span>
          </article>
          <article className="metric-card tone-amber">
            <span className="metric-label">시간외 대기</span>
            <strong className="metric-value">{pendingOvertime}</strong>
            <span className="metric-hint">승인 {approvedOvertime}건</span>
          </article>
          {isManagerUp && (
            <article className="metric-card tone-rose">
              <span className="metric-label">팀 이상</span>
              <strong className="metric-value">{anomalies.length}</strong>
              <span className="metric-hint">확정 필요 항목</span>
            </article>
          )}
        </section>

        <section className="service-grid two-column">
          <div className="card-block service-panel" data-testid="overtime-request">
            <div className="panel-heading">
              <div>
                <h2>시간외 신청</h2>
                <p>근무 일자와 시작·종료 시간을 선택하면 결재함으로 전달됩니다.</p>
              </div>
            </div>
            <form className="pro-form" onSubmit={submitOvertime}>
              <label className="field" style={{ gridColumn: '1 / -1' }}>
                <span>근무 일자</span>
                <input type="date" required value={overtimeForm.date}
                       onChange={(e) => setOvertimeForm({ ...overtimeForm, date: e.target.value })} />
              </label>
              <label className="field">
                <span>시작 시간</span>
                <input type="time" required value={overtimeForm.startTime}
                       onChange={(e) => setOvertimeForm({ ...overtimeForm, startTime: e.target.value })} />
              </label>
              <label className="field">
                <span>종료 시간</span>
                <input type="time" required value={overtimeForm.endTime}
                       onChange={(e) => setOvertimeForm({ ...overtimeForm, endTime: e.target.value })} />
              </label>
              <input type="text" required placeholder="신청 사유" style={{ gridColumn: '1 / -1' }}
                     value={overtimeForm.reason}
                     onChange={(e) => setOvertimeForm({ ...overtimeForm, reason: e.target.value })} />
              <button className="btn-primary" type="submit" style={{ gridColumn: '1 / -1' }}>신청</button>
            </form>
          </div>

          <div className="card-block service-panel">
            <div className="panel-heading">
              <div>
                <h2>내 시간외 신청</h2>
                <p>최근 신청 4건의 처리 상태입니다.</p>
              </div>
            </div>
            {overtimeMine.length === 0 ? (
              <div className="empty-state">신청 내역이 없습니다.</div>
            ) : (
              <div className="activity-list">
                {overtimeMine.slice(0, 4).map((r) => (
                  <div key={r.id} className="activity-item">
                    <span className={'status-dot ' + (r.status === 'APPROVED' ? 'success' : r.status === 'REJECTED' ? 'danger' : 'pending')} />
                    <div>
                      <strong>{r.requestDate}</strong>
                      <small>{fmtTime(r.expectedStartAt)} ~ {fmtTime(r.expectedEndAt)} · {r.reason}</small>
                      <ApproverCell status={r.status} approver={r.approver} />
                    </div>
                    <span className={'badge ' + (r.status === 'APPROVED' ? 'active' : r.status === 'REJECTED' ? 'resigned' : 'inactive')}>
                      {STATUS_LABEL[r.status] ?? r.status}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>

      {isManagerUp && (
        <>
          <section className="card-block service-panel" data-testid="attendance-anomalies">
            <div className="panel-heading">
              <div>
                <h2>팀 근태 이상</h2>
                <p>유형을 재지정하고 시간외 인정 여부를 확정합니다.</p>
              </div>
              <span className="count-chip">오늘 {anomalies.length}건</span>
            </div>
            {anomalies.length === 0 ? (
              <div className="empty-state">이상 항목이 없습니다.</div>
            ) : (
              <div className="table-shell">
                <table className="data-table">
                  <thead>
                    <tr><th>직원</th><th>일자</th><th>유형</th><th>시간외 인정</th><th>상태</th><th>처리</th></tr>
                  </thead>
                  <tbody>
                    {anomalies.map((a) => {
                      const form = confirmForms[a.id] ?? { anomalyType: a.anomalyType, overtimeApproved: true }
                      return (
                        <tr key={a.id}>
                          <td><strong>{a.userName}</strong></td>
                          <td>{a.date}</td>
                          <td>
                            <select
                              className="table-select"
                              value={form.anomalyType}
                              disabled={a.confirmed}
                              onChange={(event) => setConfirmForms({
                                ...confirmForms,
                                [a.id]: { ...form, anomalyType: event.target.value },
                              })}
                            >
                              {ANOMALY_OPTIONS.map((option) => (
                                <option key={option} value={option}>{ANOMALY_LABEL[option]}</option>
                              ))}
                            </select>
                          </td>
                          <td>
                            <label className="check-filter">
                              <input
                                type="checkbox"
                                checked={form.overtimeApproved}
                                disabled={a.confirmed || form.anomalyType !== 'UNAPPROVED_OVERTIME'}
                                onChange={(event) => setConfirmForms({
                                  ...confirmForms,
                                  [a.id]: { ...form, overtimeApproved: event.target.checked },
                                })}
                              />
                              인정
                            </label>
                          </td>
                          <td>
                            <span className={'badge ' + (a.confirmed ? 'active' : 'resigned')}>
                              {a.confirmed ? '확인됨' : '미처리'}
                            </span>
                          </td>
                          <td>
                            {!a.confirmed && (
                              <button className="btn-sm primary" onClick={() => confirm(a.id)}>확정</button>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="card-block service-panel" data-testid="overtime-inbox">
            <div className="panel-heading">
              <div>
                <h2>시간외 결재함</h2>
                <p>예상 근무 시간과 사유를 확인하고 처리합니다.</p>
              </div>
              <span className="count-chip">대기 {overtimeInbox.length}건</span>
            </div>
            {overtimeInbox.length === 0 ? <div className="empty-state">대기 중인 시간외 신청이 없습니다.</div> : (
              <div className="table-shell">
                <table className="data-table">
                  <thead><tr><th>신청자</th><th>예상 시간</th><th>사유</th><th>결재자</th><th>처리</th></tr></thead>
                  <tbody>
                    {overtimeInbox.map((r) => (
                      <tr key={r.id}>
                        <td>{r.userName ?? `사번 ${r.userId}`}</td>
                        <td>{fmtTime(r.expectedStartAt)} ~ {fmtTime(r.expectedEndAt)}</td>
                        <td>{r.reason}</td>
                        <td><ApproverCell status={r.status} approver={r.approver} /></td>
                        <td className="actions">
                          <button className="btn-sm primary" onClick={() => processOvertime(r.id, 'APPROVE')}>승인</button>
                          <button className="btn-sm" onClick={() => processOvertime(r.id, 'REJECT')}>반려</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="card-block service-panel" data-testid="monthly-close">
            <div className="panel-heading">
              <div>
                <h2>월마감</h2>
                <p>미확정 이상을 확인한 뒤 월별 근태 스냅샷을 생성합니다.</p>
              </div>
            </div>
            <div className="pro-form inline">
              <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
              <button className="btn-primary" type="button" onClick={() => closeMonth(false)}>마감 스냅샷 생성</button>
            </div>
            {monthlyResult && (
              <p className="muted small">
                {monthlyResult.closed
                  ? `${month} 마감 완료: ${monthlyResult.closedCount ?? 0}명, 시간외 ${monthlyResult.totalOvertimeMinutes ?? 0}분`
                  : monthlyResult.message}
              </p>
            )}
          </section>
        </>
      )}
      </div>
    </AppLayout>
  )
}
