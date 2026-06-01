import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { isApprover } from '../lib/roles'

interface Summary {
  userName: string
  role: string
  clockedIn: boolean
  clockOutAt: string | null
  attendanceStatus: string
  annualTotal: number
  annualUsed: number
  annualRemaining: number
  unreadNotifications: number
  pendingApprovals: number
  teamAnomaliesToday: number
  monthWorkDays: number
  monthLateCount: number
  monthOvertimeMinutes: number
}

interface ScheduleItem {
  id: number
  date: string
  startTime: string | null
  endTime: string | null
  title: string
  kind: string
}

type Tone = 'blue' | 'green' | 'amber' | 'rose' | 'slate'
type IconName = 'clock' | 'leave' | 'approval' | 'bell' | 'team'

interface MetricCardProps {
  label: string
  value: string | number
  hint?: string
  tone: Tone
  icon: IconName
}

function DashboardIcon({ name }: { name: IconName }) {
  if (name === 'clock') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="8" />
        <path d="M12 7.5v5l3.2 2" />
      </svg>
    )
  }
  if (name === 'leave') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 4.5v15" />
        <path d="M7 6h8.5l-1.8 3 1.8 3H7" />
      </svg>
    )
  }
  if (name === 'approval') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 4.5h7l3 3v12H7z" />
        <path d="M14 4.5v3h3" />
        <path d="m9.5 13 1.8 1.8 3.7-4" />
      </svg>
    )
  }
  if (name === 'bell') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M17.5 10.2c0-3-2-5.2-5.5-5.2s-5.5 2.2-5.5 5.2v2.5c0 .8-.3 1.5-.8 2.1l-.8.9h14.2l-.8-.9c-.5-.6-.8-1.3-.8-2.1z" />
        <path d="M9.8 18.2c.4.9 1.1 1.3 2.2 1.3s1.8-.4 2.2-1.3" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="9" cy="8.5" r="3" />
      <path d="M4.8 18c.7-2.4 2.1-3.6 4.2-3.6s3.5 1.2 4.2 3.6" />
      <path d="M15.5 11.5a2.5 2.5 0 1 0 0-5" />
      <path d="M15 14.7c1.9.3 3.3 1.4 4.2 3.3" />
    </svg>
  )
}

function MetricCard({ label, value, hint, tone, icon }: MetricCardProps) {
  return (
    <article className={`metric-card tone-${tone}`}>
      <span className="metric-icon"><DashboardIcon name={icon} /></span>
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{value}</strong>
      {hint && <span className="metric-hint">{hint}</span>}
    </article>
  )
}

function roleHomeLabel(role?: string | null) {
  if (role === 'CEO' || role === 'VP') return '경영 결재 허브'
  if (role === 'HR_MANAGER') return '인사 운영 허브'
  if (role === 'MANAGER') return '팀 운영 허브'
  return '개인 업무 허브'
}

function attendanceTone(summary: Summary | null): Tone {
  if (!summary) return 'slate'
  if (summary.attendanceStatus === 'ANOMALY') return 'rose'
  if (summary.clockedIn && !summary.clockOutAt) return 'green'
  if (summary.clockOutAt) return 'blue'
  return 'amber'
}

function restartTutorial() {
  window.dispatchEvent(new Event('onwork:tutorial-restart'))
}

/** 초과 근무 분 → "Xh Ym" */
function fmtOvertime(min: number): string {
  if (!min) return '0m'
  const h = Math.floor(min / 60)
  const m = min % 60
  if (h > 0) return m > 0 ? `${h}h ${m}m` : `${h}h`
  return `${m}m`
}

/** 일정 날짜 → 오늘/내일/모레/M.D */
function relDay(dateStr: string): string {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const d = new Date(`${dateStr}T00:00:00`)
  const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000)
  if (diff === 0) return '오늘'
  if (diff === 1) return '내일'
  if (diff === 2) return '모레'
  return `${d.getMonth() + 1}.${d.getDate()}`
}

function fmtTime(t: string | null): string {
  return t ? t.slice(0, 5) : ''
}

function kindLabel(kind: string): string {
  if (kind === 'TASK') return '업무'
  if (kind === 'PERSONAL') return '개인'
  return '회의'
}

export default function DashboardPage() {
  const { user } = useAuth()
  const [s, setS] = useState<Summary | null>(null)
  const [schedules, setSchedules] = useState<ScheduleItem[]>([])
  const [clocking, setClocking] = useState(false)
  const [clockMsg, setClockMsg] = useState<string | null>(null)
  const managerUp = isApprover(user?.role)

  const load = useCallback(() => {
    const summary = api.get<Summary>('/dashboard/summary')
      .then((r) => setS(r.data))
      .catch(() => setS(null))
    const sched = api.get<{ items: ScheduleItem[] }>('/schedules/me')
      .then((r) => setSchedules(r.data.items))
      .catch(() => setSchedules([]))
    return Promise.all([summary, sched])
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // 메인 화면에서 바로 출근/퇴근 (출근하기 → POST clock-in, 퇴근하기 → PATCH clock-out)
  async function handleClock(kind: 'clock-in' | 'clock-out') {
    setClocking(true)
    setClockMsg(null)
    try {
      if (kind === 'clock-out') await api.patch('/attendance/clock-out')
      else await api.post('/attendance/clock-in')
      await load()
      setClockMsg(kind === 'clock-in' ? '출근 완료 — 좋은 하루 되세요!' : '퇴근 완료 — 오늘도 수고하셨습니다!')
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      setClockMsg(msg ?? '처리에 실패했습니다')
    } finally {
      setClocking(false)
    }
  }

  const attendanceText = !s ? '-' : s.clockOutAt ? '퇴근 완료' : s.clockedIn ? '근무 중' : '미출근'
  const tone = attendanceTone(s)
  const pendingCount = s?.pendingApprovals ?? 0
  const unreadCount = s?.unreadNotifications ?? 0
  const anomalyCount = s?.teamAnomaliesToday ?? 0
  const focusText = pendingCount > 0
    ? `${pendingCount}건의 결재가 대기 중입니다.`
    : unreadCount > 0
      ? `${unreadCount}개의 읽지 않은 알림이 있습니다.`
      : attendanceText === '미출근'
        ? '출근 기록부터 시작하세요.'
        : '오늘 필요한 업무가 안정적으로 정리되어 있습니다.'

  return (
    <AppLayout>
      <div className="dashboard-page">
        <section className="dashboard-hero solo" data-testid="dashboard-widgets">
          <div className="dashboard-hero-copy">
            <div className="dashboard-mode">
              <span className={`live-dot tone-${tone}`} />
              <span>{roleHomeLabel(user?.role)}</span>
            </div>
            <h1>안녕하세요, {s?.userName ?? user?.name ?? ''}님</h1>
            <p>{focusText}</p>
            <div className="dashboard-hero-actions">
              {attendanceText === '미출근' ? (
                <button className="dashboard-primary-link" type="button" disabled={!s || clocking} onClick={() => handleClock('clock-in')}>
                  {clocking ? '출근 처리 중…' : '출근하기'}
                </button>
              ) : attendanceText === '근무 중' ? (
                <button className="dashboard-primary-link" type="button" disabled={clocking} onClick={() => handleClock('clock-out')}>
                  {clocking ? '퇴근 처리 중…' : '퇴근하기'}
                </button>
              ) : (
                <Link className="dashboard-primary-link" to={managerUp && pendingCount > 0 ? '/approvals' : '/attendance'}>
                  {managerUp && pendingCount > 0 ? '결재함 확인' : '근태 보기'}
                </Link>
              )}
              <button className="dashboard-secondary-link" type="button" onClick={restartTutorial}>가이드 보기</button>
            </div>
            {clockMsg && <p className="dashboard-clock-msg" role="status">{clockMsg}</p>}
          </div>
        </section>

        <section className="metric-grid" aria-label="오늘 요약">
          <MetricCard label="오늘 근태" value={attendanceText} hint={s?.attendanceStatus === 'ANOMALY' ? '이상 있음' : 'Asia/Seoul 기준'} tone={tone} icon="clock" />
          <MetricCard label="연차 잔여" value={s ? `${s.annualRemaining}일` : '-'} hint={s ? `총 ${s.annualTotal} · 사용 ${s.annualUsed}` : ''} tone="green" icon="leave" />
          <MetricCard label="결재 대기" value={pendingCount} hint="내가 처리할 결재" tone={pendingCount > 0 ? 'blue' : 'slate'} icon="approval" />
          <MetricCard label="읽지 않은 알림" value={unreadCount} hint={unreadCount > 0 ? '확인 필요' : '모두 확인'} tone={unreadCount > 0 ? 'rose' : 'slate'} icon="bell" />
          {managerUp && <MetricCard label="팀 근태 이상" value={anomalyCount} hint="오늘" tone={anomalyCount > 0 ? 'amber' : 'green'} icon="team" />}
        </section>

        <section className="dashboard-workbench">
          <div className="dashboard-panel priority-panel">
            <div className="panel-heading">
              <div>
                <h2>오늘의 일정</h2>
                <p>오늘과 다가오는 일정을 한눈에 확인하세요.</p>
              </div>
            </div>
            <div className="priority-list" data-testid="today-schedule">
              {schedules.length === 0 ? (
                <div className="empty-state">예정된 일정이 없습니다.</div>
              ) : (
                schedules.map((it) => (
                  <div key={it.id} className="priority-row schedule-row tone-blue">
                    <span className="when">{relDay(it.date)}</span>
                    <span>
                      <strong>{it.title}</strong>
                      <small>
                        {fmtTime(it.startTime)}{it.endTime ? ` ~ ${fmtTime(it.endTime)}` : ''} · {kindLabel(it.kind)}
                      </small>
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>

          <div className="dashboard-panel flow-panel">
            <div className="panel-heading">
              <div>
                <h2>이번 달 근태 요약</h2>
                <p>이번 달 내 근태 현황을 한눈에 확인합니다.</p>
              </div>
            </div>
            <div className="month-summary" data-testid="month-attendance">
              <div>
                <span className="ms-label">이번 달 출근일</span>
                <strong className="ms-value">{s?.monthWorkDays ?? 0}일</strong>
              </div>
              <div>
                <span className="ms-label">지각 횟수</span>
                <strong className="ms-value">{s?.monthLateCount ?? 0}회</strong>
              </div>
              <div>
                <span className="ms-label">초과 근무</span>
                <strong className="ms-value">{fmtOvertime(s?.monthOvertimeMinutes ?? 0)}</strong>
              </div>
            </div>
          </div>
        </section>
      </div>
    </AppLayout>
  )
}
