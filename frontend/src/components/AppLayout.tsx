import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { api } from '../lib/api'
import { hrSurfaceLabel, isApprover } from '../lib/roles'
import OnboardingGuide from './OnboardingGuide'

const NAV = [
  { to: '/dashboard', label: '홈', icon: 'home', show: () => true },
  { to: '/hr', label: (role?: string | null) => hrSurfaceLabel(role), icon: 'people', show: () => true },
  { to: '/attendance', label: '근태', icon: 'clock', show: () => true },
  { to: '/leave', label: '휴가', icon: 'flag', show: () => true },
  { to: '/approvals', label: '결재함', icon: 'approval', show: (role?: string | null) => isApprover(role) },
]

interface DigestItem {
  id: number
  type: string
  message: string
  refType: string | null
  refId: number | null
  read: boolean
}
interface Digest {
  unread: number
  pendingApprovals: number
  longPending: number
  recentApproved: number
  recentItems: DigestItem[]
}

function BellIcon() {
  return (
    <svg className="bell-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M18 9.8c0-3.3-2.2-5.8-6-5.8S6 6.5 6 9.8v2.8c0 .9-.3 1.7-.9 2.4l-.8.9c-.4.5-.1 1.2.6 1.2h14.2c.7 0 1-.8.6-1.2l-.8-.9c-.6-.7-.9-1.5-.9-2.4V9.8Z" />
      <path d="M9.6 18.5c.4 1 1.2 1.5 2.4 1.5s2-.5 2.4-1.5" />
    </svg>
  )
}

function NavIcon({ name }: { name: string }) {
  if (name === 'home') {
    return (
      <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M4.5 11 12 4.8 19.5 11" />
        <path d="M6.5 10.2v8.3h4v-4h3v4h4v-8.3" />
      </svg>
    )
  }
  if (name === 'people') {
    return (
      <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="9" cy="8.5" r="3" />
        <path d="M4.8 18c.7-2.4 2.1-3.6 4.2-3.6s3.5 1.2 4.2 3.6" />
        <path d="M15.5 11.5a2.5 2.5 0 1 0 0-5" />
        <path d="M15 14.7c1.9.3 3.3 1.4 4.2 3.3" />
      </svg>
    )
  }
  if (name === 'clock') {
    return (
      <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="8" />
        <path d="M12 7.5v5l3.2 2" />
      </svg>
    )
  }
  if (name === 'flag') {
    return (
      <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 4.5v15" />
        <path d="M7 6h8.5l-1.8 3 1.8 3H7" />
      </svg>
    )
  }
  return (
    <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 4.5h7l3 3v12H7z" />
      <path d="M14 4.5v3h3" />
      <path d="m9.5 13 1.8 1.8 3.7-4" />
    </svg>
  )
}

export default function AppLayout({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [digest, setDigest] = useState<Digest | null>(null)
  const [open, setOpen] = useState(false)
  const [resetting, setResetting] = useState(false)

  // 시연용: 더미데이터 초기화 + 개발팀장·기획팀장 오늘 휴가 세팅
  async function onDemoReset() {
    if (resetting) return
    const ok = window.confirm(
      '시연 초기화\n\n· 시연 중 추가/변경된 데이터를 모두 지우고 기본 더미데이터로 되돌립니다.\n· 개발팀장·영업팀장을 오늘 휴가 상태로 만듭니다(대행 결재 시연용).\n\n진행할까요?',
    )
    if (!ok) return
    setResetting(true)
    try {
      await api.post('/admin/demo-reset')
      window.location.reload()
    } catch {
      window.alert('초기화에 실패했습니다. 잠시 후 다시 시도해주세요.')
      setResetting(false)
    }
  }

  const fetchDigest = useCallback(async () => {
    try {
      const r = await api.get<Digest>('/notifications/digest')
      setDigest(r.data)
    } catch {
      setDigest(null)
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void fetchDigest()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [fetchDigest])

  async function markAllRead() {
    await api.patch('/notifications/read-all')
    await fetchDigest()
  }

  async function openNotification(item: DigestItem) {
    await api.patch(`/notifications/${item.id}/read`)
    setNotificationsOpen(false)
    await fetchDigest()
    const refType = item.refType ?? item.type.split('_')[0]
    if (refType === 'LEAVE') navigate('/leave')
    else if (refType === 'ATTENDANCE') navigate('/attendance')
    else if (refType === 'HR') navigate('/hr')
    else navigate('/approvals')
  }

  const setNotificationsOpen = useCallback((nextOpen: boolean) => {
    setOpen(nextOpen)
    if (nextOpen) void fetchDigest()
  }, [fetchDigest])

  useEffect(() => {
    function onOpenNotifications() {
      setNotificationsOpen(true)
    }
    window.addEventListener('onwork:notifications-open', onOpenNotifications)
    return () => window.removeEventListener('onwork:notifications-open', onOpenNotifications)
  }, [setNotificationsOpen])

  async function onLogout() {
    await logout()
    navigate('/login')
  }

  const unread = digest?.unread ?? 0
  const navItems = NAV
    .filter((item) => item.show(user?.role))
    .map((item) => ({
      to: item.to,
      label: typeof item.label === 'function' ? item.label(user?.role) : item.label,
      icon: item.icon,
    }))

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-brand">OnWork</div>
        <nav>
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
            >
              <NavIcon name={item.icon} />
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <button
          className="sidebar-reset"
          onClick={onDemoReset}
          disabled={resetting}
          title="시연용 — 더미데이터 초기화 + 개발/영업팀장 휴가 세팅"
        >
          {resetting ? '초기화 중…' : '⟲ 시연 초기화'}
        </button>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="topbar-spacer" />
          <div className="bell-wrap">
            <button
              className="bell"
              data-testid="notif-bell"
              onClick={() => setNotificationsOpen(!open)}
              title="알림"
              aria-label="알림"
            >
              <BellIcon />
              {unread > 0 && <span className="bell-badge">{unread}</span>}
            </button>
            {open && digest && (
              <div className="bell-dropdown" data-testid="notif-dropdown">
                <div className="bell-summary">
                  <div><strong>{digest.pendingApprovals}</strong><span>결재 대기</span></div>
                  <div><strong>{digest.longPending}</strong><span>긴급</span></div>
                  <div><strong>{digest.unread}</strong><span>읽지 않음</span></div>
                </div>
                <ul className="bell-list">
                  {digest.recentItems.length === 0 ? (
                    <li className="muted small">최근 알림이 없습니다.</li>
                  ) : (
                    digest.recentItems.map((n) => (
                      <li key={n.id} className={n.read ? 'read' : 'unread'}>
                        <button className="bell-item" onClick={() => openNotification(n)}>
                          <span className="bell-type">{n.type.split('_')[0]}</span>
                          <span>{n.message}</span>
                        </button>
                      </li>
                    ))
                  )}
                </ul>
                <div className="bell-actions">
                  <button className="btn-sm" onClick={markAllRead}>전체 읽음</button>
                </div>
              </div>
            )}
          </div>
          <div className="topbar-user" data-testid="topbar-user">
            <span className="user-name">{user?.name ?? '사용자'}</span>
            <span className="user-role">{user?.position ?? user?.role}</span>
            <button className="btn-ghost" onClick={onLogout} data-testid="logout-button">
              로그아웃
            </button>
          </div>
        </header>
        <section className="content">{children}</section>
        <OnboardingGuide onSetNotificationsOpen={setNotificationsOpen} />
      </div>
    </div>
  )
}
