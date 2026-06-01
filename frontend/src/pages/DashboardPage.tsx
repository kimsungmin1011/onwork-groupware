import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { hrSurfaceLabel, isApprover } from '../lib/roles'

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
}

type Tone = 'blue' | 'green' | 'amber' | 'rose' | 'slate'
type IconName = 'clock' | 'leave' | 'approval' | 'bell' | 'team' | 'user' | 'spark'

interface MetricCardProps {
  label: string
  value: string | number
  hint?: string
  tone: Tone
  icon: IconName
}

interface PriorityItem {
  title: string
  value: string | number
  description: string
  tone: Tone
  href?: string
  action?: () => void
}

interface QuickLinkItem {
  label: string
  description: string
  icon: IconName
  href?: string
  action?: () => void
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
  if (name === 'team') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="9" cy="8.5" r="3" />
        <path d="M4.8 18c.7-2.4 2.1-3.6 4.2-3.6s3.5 1.2 4.2 3.6" />
        <path d="M15.5 11.5a2.5 2.5 0 1 0 0-5" />
        <path d="M15 14.7c1.9.3 3.3 1.4 4.2 3.3" />
      </svg>
    )
  }
  if (name === 'user') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="8.5" r="3.5" />
        <path d="M5.8 19c1-3 3-4.5 6.2-4.5s5.2 1.5 6.2 4.5" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 4.5 13.5 9l4.5 1.5-4.5 1.5L12 16.5 10.5 12 6 10.5 10.5 9z" />
      <path d="m18 15 .7 2.1L21 18l-2.3.9L18 21l-.7-2.1L15 18l2.3-.9z" />
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

function openNotifications() {
  window.dispatchEvent(new Event('onwork:notifications-open'))
}

function restartTutorial() {
  window.dispatchEvent(new Event('onwork:tutorial-restart'))
}

export default function DashboardPage() {
  const { user } = useAuth()
  const [s, setS] = useState<Summary | null>(null)
  const [clocking, setClocking] = useState(false)
  const [clockMsg, setClockMsg] = useState<string | null>(null)
  const managerUp = isApprover(user?.role)

  const load = useCallback(() => {
    return api.get<Summary>('/dashboard/summary')
      .then((r) => setS(r.data))
      .catch(() => setS(null))
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
  const leaveUsage = s && s.annualTotal > 0 ? Math.min(100, Math.round((s.annualUsed / s.annualTotal) * 100)) : 0
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
  const statusDescription = s?.attendanceStatus === 'ANOMALY'
    ? '근태 이상 확인이 필요합니다'
    : attendanceText === '근무 중'
      ? '업무 시간이 기록되고 있습니다'
      : attendanceText === '퇴근 완료'
        ? '오늘 근무가 마감되었습니다'
        : '아직 출근 기록이 없습니다'
  const priorities: PriorityItem[] = [
    ...(managerUp ? [{
      title: '결재 대기',
      value: pendingCount,
      description: pendingCount > 0 ? '승인 또는 보류가 필요한 요청' : '대기 중인 결재 없음',
      tone: pendingCount > 0 ? 'blue' as const : 'slate' as const,
      href: '/approvals',
    }] : []),
    ...(managerUp ? [{
      title: '팀 근태 이상',
      value: anomalyCount,
      description: anomalyCount > 0 ? '오늘 확정이 필요한 근태 건' : '팀 근태 이상 없음',
      tone: anomalyCount > 0 ? 'amber' as const : 'green' as const,
      href: '/attendance',
    }] : []),
    {
      title: '읽지 않은 알림',
      value: unreadCount,
      description: unreadCount > 0 ? '최근 업무 알림 확인 필요' : '새 알림 없음',
      tone: unreadCount > 0 ? 'rose' : 'slate',
      action: openNotifications,
    },
    {
      title: '연차 잔여',
      value: s ? `${s.annualRemaining}일` : '-',
      description: '휴가 신청 전 잔여일 확인',
      tone: 'green',
      href: '/leave',
    },
  ]
  const quickLinks: QuickLinkItem[] = [
    { label: '근태', description: attendanceText === '미출근' ? '출근 기록하기' : '오늘 기록 확인', icon: 'clock', href: '/attendance' },
    { label: '휴가', description: '잔여일과 신청 내역', icon: 'leave', href: '/leave' },
    { label: hrSurfaceLabel(user?.role), description: user?.role === 'EMPLOYEE' ? '내 정보 확인' : '직원 정보 관리', icon: 'user', href: '/hr' },
    ...(managerUp ? [{ label: '결재함', description: '승인 대기 모아보기', icon: 'approval' as const, href: '/approvals' }] : []),
    { label: '알림', description: '최근 알림 열기', icon: 'bell', action: openNotifications },
    { label: '온보딩', description: '가이드 다시 보기', icon: 'spark', action: restartTutorial },
  ]

  return (
    <AppLayout>
      <div className="dashboard-page">
        <section className="dashboard-hero" data-testid="dashboard-widgets">
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

          <div className={`today-card tone-${tone}`}>
            <div>
              <span className="today-label">오늘 근무 상태</span>
              <strong>{attendanceText}</strong>
              <p>{statusDescription}</p>
            </div>
            <div className="today-progress" aria-label="연차 사용률">
              <span style={{ width: `${leaveUsage}%` }} />
            </div>
            <div className="today-meta">
              <span>연차 사용률 {leaveUsage}%</span>
              <strong>{s ? `${s.annualRemaining}일 남음` : '-'}</strong>
            </div>
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
                <h2>오늘 우선 처리</h2>
                <p>지금 확인하면 좋은 업무만 모았습니다.</p>
              </div>
            </div>
            <div className="priority-list">
              {priorities.map((item) => (
                item.href ? (
                  <Link key={item.title} className={`priority-row tone-${item.tone}`} to={item.href}>
                    <span className="priority-value">{item.value}</span>
                    <span>
                      <strong>{item.title}</strong>
                      <small>{item.description}</small>
                    </span>
                  </Link>
                ) : (
                  <button key={item.title} className={`priority-row tone-${item.tone}`} type="button" onClick={item.action}>
                    <span className="priority-value">{item.value}</span>
                    <span>
                      <strong>{item.title}</strong>
                      <small>{item.description}</small>
                    </span>
                  </button>
                )
              ))}
            </div>
          </div>

          <div className="dashboard-panel flow-panel">
            <div className="panel-heading">
              <div>
                <h2>업무 흐름</h2>
                <p>근태, 휴가, 결재, 알림이 한 흐름으로 이어집니다.</p>
              </div>
            </div>
            <ol className="workflow-steps">
              <li className={attendanceText !== '미출근' ? 'active' : ''}>
                <span><DashboardIcon name="clock" /></span>
                <div><strong>근태 기록</strong><small>{attendanceText}</small></div>
              </li>
              <li className={s && s.annualRemaining > 0 ? 'active' : ''}>
                <span><DashboardIcon name="leave" /></span>
                <div><strong>휴가 확인</strong><small>{s ? `${s.annualRemaining}일 사용 가능` : '-'}</small></div>
              </li>
              <li className={pendingCount > 0 ? 'active' : ''}>
                <span><DashboardIcon name="approval" /></span>
                <div><strong>결재 처리</strong><small>{pendingCount}건 대기</small></div>
              </li>
              <li className={unreadCount > 0 ? 'active' : ''}>
                <span><DashboardIcon name="bell" /></span>
                <div><strong>알림 확인</strong><small>{unreadCount}개 미확인</small></div>
              </li>
            </ol>
          </div>
        </section>

        <section className="quick-action-grid" aria-label="빠른 이동">
          {quickLinks.map((item) => (
            item.href ? (
              <Link key={item.label} className="quick-action" to={item.href}>
                <span><DashboardIcon name={item.icon} /></span>
                <strong>{item.label}</strong>
                <small>{item.description}</small>
              </Link>
            ) : (
              <button key={item.label} className="quick-action" type="button" onClick={item.action}>
                <span><DashboardIcon name={item.icon} /></span>
                <strong>{item.label}</strong>
                <small>{item.description}</small>
              </button>
            )
          ))}
        </section>
      </div>
    </AppLayout>
  )
}
