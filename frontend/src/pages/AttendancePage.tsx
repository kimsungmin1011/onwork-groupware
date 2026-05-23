import { useCallback, useEffect, useState } from 'react'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'

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

const ANOMALY_LABEL: Record<string, string> = {
  LATE: '지각', EARLY_LEAVE: '조퇴', ABSENT: '결근',
  CLOCK_MISSING: '퇴근누락', UNAPPROVED_OVERTIME: '미승인 시간외',
}

function fmtTime(iso: string | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

export default function AttendancePage() {
  const { user } = useAuth()
  const isManagerUp = user ? ['CEO', 'VP', 'HR_MANAGER', 'MANAGER'].includes(user.role) : false

  const [today, setToday] = useState<Today | null>(null)
  const [anomalies, setAnomalies] = useState<Anomaly[]>([])
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const t = await api.get<Today>('/attendance/today')
    setToday(t.data)
    if (isManagerUp) {
      const a = await api.get<{ items: Anomaly[] }>('/attendance/anomalies')
      setAnomalies(a.data.items)
    }
  }, [isManagerUp])

  useEffect(() => {
    load()
  }, [load])

  async function clock(kind: 'clock-in' | 'clock-out') {
    setBusy(true)
    try {
      await api.post(`/attendance/${kind}`)
      await load()
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      alert(msg ?? '처리에 실패했습니다')
    } finally {
      setBusy(false)
    }
  }

  async function confirm(id: number) {
    await api.patch(`/attendance/anomalies/${id}/confirm`)
    await load()
  }

  const clockedIn = !!today?.clockInAt
  const clockedOut = !!today?.clockOutAt

  return (
    <AppLayout>
      <h1 className="page-title">근태관리</h1>

      <section className="card-block" data-testid="attendance-today">
        <h2 className="section-title">오늘 근태 ({today?.date ?? '-'})</h2>
        <div className="clock-row">
          <div className="clock-stat">
            <span className="muted">출근</span>
            <strong>{fmtTime(today?.clockInAt ?? null)}</strong>
          </div>
          <div className="clock-stat">
            <span className="muted">퇴근</span>
            <strong>{fmtTime(today?.clockOutAt ?? null)}</strong>
          </div>
          <div className="clock-actions">
            <button className="btn-primary" disabled={busy || clockedIn} onClick={() => clock('clock-in')}>
              출근
            </button>
            <button className="btn-ghost" disabled={busy || !clockedIn || clockedOut} onClick={() => clock('clock-out')}>
              퇴근
            </button>
          </div>
        </div>
      </section>

      {isManagerUp && (
        <section className="card-block" data-testid="attendance-anomalies">
          <h2 className="section-title">팀 근태 이상 (오늘 {anomalies.length}건)</h2>
          {anomalies.length === 0 ? (
            <p className="muted">이상 항목이 없습니다.</p>
          ) : (
            <table className="data-table">
              <thead>
                <tr><th>직원</th><th>유형</th><th>상태</th><th>처리</th></tr>
              </thead>
              <tbody>
                {anomalies.map((a) => (
                  <tr key={a.id}>
                    <td>{a.userName}</td>
                    <td>{ANOMALY_LABEL[a.anomalyType] ?? a.anomalyType}</td>
                    <td>
                      <span className={'badge ' + (a.confirmed ? 'active' : 'resigned')}>
                        {a.confirmed ? '확인됨' : '미처리'}
                      </span>
                    </td>
                    <td>
                      {!a.confirmed && (
                        <button className="btn-sm primary" onClick={() => confirm(a.id)}>확인</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </AppLayout>
  )
}
