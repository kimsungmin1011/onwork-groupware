import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { hrSurfaceLabel, isApprover } from '../lib/roles'

interface Tutorial {
  tutorialCode: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'DISMISSED'
  currentStep: number
  autoVisible: boolean
  expired: boolean
}

interface TourStep {
  route: string
  title: string
  body: string
  target: string
  placement?: 'top' | 'bottom' | 'left' | 'right'
  action?: 'notifications'
}

interface Props {
  onSetNotificationsOpen: (open: boolean) => void
}

const TOUR_SESSION_KEY = 'onwork.tour.active'
const TARGET_PADDING = 8
const BOX_EPSILON = 0.5

interface TargetBox {
  top: number
  left: number
  width: number
  height: number
}

export default function OnboardingGuide({ onSetNotificationsOpen }: Props) {
  const { user } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [tutorial, setTutorial] = useState<Tutorial | null>(null)
  const [sessionActive, setSessionActive] = useState(() => sessionStorage.getItem(TOUR_SESSION_KEY) === '1')
  const [targetBox, setTargetBox] = useState<TargetBox | null>(null)

  const preferredCode = user?.role === 'MANAGER' ? 'MANAGER_TOUR' : 'NEW_HIRE_TOUR'

  const setTourSession = useCallback((active: boolean) => {
    setSessionActive(active)
    if (active) sessionStorage.setItem(TOUR_SESSION_KEY, '1')
    else sessionStorage.removeItem(TOUR_SESSION_KEY)
  }, [])

  const steps = useMemo<TourStep[]>(() => {
    if (tutorial?.tutorialCode === 'MANAGER_TOUR') {
      return [
        {
          route: '/hr',
          title: '팀원 정보 확인',
          body: '팀원 목록과 직원 상세를 열어 팀 구성원의 기본 정보를 확인합니다.',
          target: '[data-testid="employee-table"], [data-testid="my-profile"]',
          placement: 'right',
        },
        {
          route: '/attendance',
          title: '팀 근태 이상 처리',
          body: '팀 근태 이상 목록에서 유형을 확인하고 필요한 경우 재분류 후 확정합니다.',
          target: '[data-testid="attendance-anomalies"]',
          placement: 'top',
        },
        {
          route: '/leave',
          title: '휴가 결재',
          body: '휴가 결재함에서 신청 내용을 확인하고 승인, 보류, 승인 취소까지 처리합니다.',
          target: '[data-testid="leave-inbox"]',
          placement: 'top',
        },
        {
          route: '/approvals',
          title: '통합 결재함',
          body: '휴가, 시간외, 인사 결재를 유형별로 나누고 긴급 항목을 우선 처리합니다.',
          target: '[data-testid="approvals-inbox"]',
          placement: 'left',
        },
      ]
    }
    const base: TourStep[] = [
      {
        route: '/dashboard',
        title: '홈 요약 확인',
        body: '오늘 근태, 연차 잔여, 결재 대기, 읽지 않은 알림을 첫 화면에서 확인합니다.',
        target: '[data-testid="dashboard-widgets"]',
        placement: 'bottom',
      },
      {
        route: '/dashboard',
        title: '알림 열어보기',
        body: '헤더의 알림 패널을 자동으로 열어 최근 결재와 근태 알림을 확인합니다.',
        target: '[data-testid="notif-dropdown"]',
        placement: 'left',
        action: 'notifications',
      },
      {
        route: '/attendance',
        title: '근태 기록',
        body: '출근과 퇴근 버튼 상태를 확인하고 오늘 근무 기록을 남깁니다.',
        target: '[data-testid="attendance-today"]',
        placement: 'bottom',
      },
      {
        route: '/leave',
        title: '휴가 신청',
        body: '휴가 잔여일을 확인하고 연차, 반차, 보상휴가를 신청합니다.',
        target: '[data-testid="leave-request-form"]',
        placement: 'top',
      },
      {
        route: '/hr',
        title: hrSurfaceLabel(user?.role),
        body: user?.role === 'EMPLOYEE'
          ? '내 인사 정보와 계정 정보를 확인합니다.'
          : user?.role === 'MANAGER'
            ? '팀원 목록과 기본 인사 정보를 확인합니다.'
            : '직원 정보, 신규 입사 등록, 인사 변경 요청을 관리합니다.',
        target: user?.role === 'EMPLOYEE'
          ? '[data-testid="my-profile"]'
          : user?.role === 'HR_MANAGER'
            ? '[data-testid="hire-section"], [data-testid="employee-table"]'
            : '[data-testid="employee-table"]',
        placement: 'right',
      },
    ]
    if (isApprover(user?.role)) {
      base.push({
        route: '/approvals',
        title: '통합 결재함',
        body: '결재 행을 열어 신청 원문과 상세 내용을 확인한 뒤 승인 또는 반려합니다.',
        target: '[data-testid="approvals-inbox"]',
        placement: 'left',
      })
    }
    return base
  }, [tutorial?.tutorialCode, user?.role])

  const loadTutorial = useCallback(async () => {
    try {
      const { data } = await api.get<{ items: Tutorial[] }>('/onboarding/tutorials/me')
      setTutorial(data.items.find((item) => item.tutorialCode === preferredCode) ?? data.items[0] ?? null)
    } catch {
      setTutorial(null)
    }
  }, [preferredCode])

  const restartTutorial = useCallback(async () => {
    const code = tutorial?.tutorialCode ?? preferredCode
    const { data } = await api.post<Tutorial>(`/onboarding/tutorials/me/${code}/restart`)
    setTutorial(data)
    setTourSession(true)
    onSetNotificationsOpen(false)
  }, [onSetNotificationsOpen, preferredCode, setTourSession, tutorial?.tutorialCode])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadTutorial()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadTutorial])

  useEffect(() => {
    function onRestart() {
      void restartTutorial()
    }
    window.addEventListener('onwork:tutorial-restart', onRestart)
    return () => window.removeEventListener('onwork:tutorial-restart', onRestart)
  }, [restartTutorial])

  // 자동 노출은 첫 방문(NOT_STARTED)에서만. 진행 중(IN_PROGRESS) 투어는
  // 이번 세션에 사용자가 직접 시작/재개한 경우(sessionActive)에만 보여준다.
  // 그렇지 않으면 이전 세션에 남은 IN_PROGRESS 기록이 매 로그인마다 화면을
  // 가로채는 문제가 생긴다. (재개는 "가이드 보기"로)
  const isVisible = tutorial
    && !tutorial.expired
    && !['COMPLETED', 'DISMISSED'].includes(tutorial.status)
    && (sessionActive || (tutorial.autoVisible && tutorial.status === 'NOT_STARTED'))
  const activeIndex = Math.min(Math.max(tutorial?.currentStep ?? 0, 0), Math.max(steps.length - 1, 0))
  const activeStep = steps[activeIndex]
  const introLastSurface = isApprover(user?.role) ? '결재함' : `${hrSurfaceLabel(user?.role)} 화면`

  useEffect(() => {
    // 투어가 이번 세션에 활성화된 경우에만 화면을 자동 이동시킨다.
    // sessionActive 가드가 없으면 남아있는 IN_PROGRESS 기록이 로그인 때마다
    // 사용자를 해당 스텝 화면(예: 결재함)으로 강제 이동시킨다.
    if (!sessionActive || !tutorial || tutorial.status !== 'IN_PROGRESS' || !activeStep) return
    if (location.pathname !== activeStep.route) {
      navigate(activeStep.route)
      return
    }
    onSetNotificationsOpen(activeStep.action === 'notifications')
  }, [
    sessionActive,
    activeStep,
    location.pathname,
    navigate,
    onSetNotificationsOpen,
    tutorial,
  ])

  useEffect(() => {
    if (!isVisible || tutorial?.status !== 'IN_PROGRESS' || !activeStep) {
      return
    }

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame = 0
    let retryTimer = 0
    let attempts = 0
    let target: HTMLElement | null = null
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measureTarget) : null

    function measureTarget() {
      if (!target) return
      window.cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(() => {
        if (!target) return
        const rect = target.getBoundingClientRect()
        const next = {
          top: Math.max(rect.top - TARGET_PADDING, TARGET_PADDING),
          left: Math.max(rect.left - TARGET_PADDING, TARGET_PADDING),
          width: rect.width + TARGET_PADDING * 2,
          height: rect.height + TARGET_PADDING * 2,
        }
        setTargetBox((prev) => (sameBox(prev, next) ? prev : next))
      })
    }

    function prepareTarget() {
      const found = document.querySelector<HTMLElement>(activeStep.target)
      if (!found) {
        attempts += 1
        if (attempts < 10) {
          retryTimer = window.setTimeout(prepareTarget, 120)
          return
        }
        setTargetBox(null)
        return
      }
      attempts = 0
      target = found
      target.scrollIntoView({ block: 'center', inline: 'center', behavior: reduceMotion ? 'auto' : 'smooth' })
      observer?.observe(target)
      window.setTimeout(measureTarget, reduceMotion ? 0 : 220)
    }

    const timer = window.setTimeout(() => {
      prepareTarget()
    }, activeStep.action === 'notifications' ? 220 : 90)
    window.addEventListener('resize', measureTarget)
    window.addEventListener('scroll', measureTarget, true)
    return () => {
      window.cancelAnimationFrame(frame)
      window.clearTimeout(timer)
      window.clearTimeout(retryTimer)
      observer?.disconnect()
      window.removeEventListener('resize', measureTarget)
      window.removeEventListener('scroll', measureTarget, true)
    }
  }, [activeStep, isVisible, location.pathname, tutorial?.status])

  async function startTour() {
    if (!tutorial) return
    const { data } = await api.patch<Tutorial>(`/onboarding/tutorials/me/${tutorial.tutorialCode}/step`, {
      step: 0,
    })
    setTutorial(data)
    setTourSession(true)
  }

  async function goNext() {
    if (!tutorial) return
    onSetNotificationsOpen(false)
    if (activeIndex >= steps.length - 1) {
      const { data } = await api.patch<Tutorial>(`/onboarding/${tutorial.tutorialCode}`, {
        action: 'COMPLETE',
      })
      setTutorial(data)
      setTourSession(false)
      return
    }
    const { data } = await api.patch<Tutorial>(`/onboarding/tutorials/me/${tutorial.tutorialCode}/step`, {
      step: activeIndex + 1,
    })
    setTutorial(data)
  }

  async function snoozeTour() {
    if (!tutorial) return
    onSetNotificationsOpen(false)
    const { data } = await api.patch<Tutorial>(`/onboarding/tutorials/me/${tutorial.tutorialCode}/visibility`, {
      visible: false,
    })
    setTutorial(data)
    setTourSession(false)
  }

  async function dismissTour() {
    if (!tutorial) return
    if (!window.confirm('튜토리얼을 다시 보지 않으시겠어요? 대시보드에서 다시 시작할 수 있습니다.')) return
    onSetNotificationsOpen(false)
    const { data } = await api.patch<Tutorial>(`/onboarding/${tutorial.tutorialCode}`, {
      action: 'DISMISS',
    })
    setTutorial(data)
    setTourSession(false)
  }

  if (!isVisible || !activeStep) return null

  if (tutorial.status === 'NOT_STARTED') {
    return (
      <div className="tour-intro-backdrop" role="presentation">
        <section className="tour-intro" role="dialog" aria-modal="true" aria-labelledby="tour-intro-title">
          <p className="tour-kicker">처음 시작하기</p>
          <h2 id="tour-intro-title">OnWork 핵심 흐름을 둘러볼까요?</h2>
          <p>
            안내를 시작하면 화면이 자동으로 이동하면서 홈, 알림, 근태, 휴가,
            {' '}{introLastSurface}을 순서대로 보여줍니다.
          </p>
          <div className="tour-intro-actions">
            <button className="btn-primary" onClick={startTour}>튜토리얼 시작</button>
            <button className="btn-ghost" onClick={snoozeTour}>나중에 보기</button>
          </div>
        </section>
      </div>
    )
  }

  const placement = activeStep.placement ?? 'bottom'
  const popoverStyle = tourPopoverStyle(targetBox, placement)
  const ringStyle: CSSProperties | undefined = targetBox
    ? {
        top: targetBox.top,
        left: targetBox.left,
        width: targetBox.width,
        height: targetBox.height,
      }
    : undefined

  return (
    <div className="tour-overlay-root" data-testid="onboarding-guide">
      <div className="tour-click-catcher" />
      {targetBox ? (
        <div className="tour-target-ring" style={ringStyle} />
      ) : (
        <div className="tour-mask full" />
      )}
      <aside
        className={`tour-popover placement-${placement}`}
        style={popoverStyle}
        aria-live="polite"
        role="dialog"
        aria-modal="false"
      >
        <button className="tour-close" type="button" onClick={snoozeTour} aria-label="튜토리얼 닫기">×</button>
        <div className="tour-progress">
          <span>{activeIndex + 1} / {steps.length}</span>
          <div className="tour-progress-track">
            <span style={{ width: `${((activeIndex + 1) / steps.length) * 100}%` }} />
          </div>
        </div>
        <h2>{activeStep.title}</h2>
        <p>{activeStep.body}</p>
        <div className="tour-dots" aria-hidden="true">
          {steps.map((step, index) => (
            <span key={`${step.route}-${step.title}`} className={index === activeIndex ? 'active' : ''} />
          ))}
        </div>
        <div className="tour-panel-actions">
          <button className="btn-sm primary" onClick={goNext}>
            {activeIndex >= steps.length - 1 ? '완료' : '다음 기능 보기'}
          </button>
          <button className="btn-sm" onClick={snoozeTour}>나중에</button>
          <button className="btn-sm" onClick={dismissTour}>그만보기</button>
        </div>
      </aside>
    </div>
  )
}

function sameBox(current: TargetBox | null, next: TargetBox) {
  if (!current) return false
  return Math.abs(current.top - next.top) < BOX_EPSILON
    && Math.abs(current.left - next.left) < BOX_EPSILON
    && Math.abs(current.width - next.width) < BOX_EPSILON
    && Math.abs(current.height - next.height) < BOX_EPSILON
}

function tourPopoverStyle(target: TargetBox | null, placement: NonNullable<TourStep['placement']>): CSSProperties {
  if (!target) {
    return {
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
    }
  }

  const gap = 14
  const width = Math.min(390, window.innerWidth - 32)
  const estimatedHeight = 238
  let top = target.top + target.height + gap
  let left = target.left

  if (placement === 'top') {
    top = target.top - estimatedHeight - gap
    left = target.left
  } else if (placement === 'left') {
    top = target.top
    left = target.left - width - gap
  } else if (placement === 'right') {
    top = target.top
    left = target.left + target.width + gap
  }

  if (top + estimatedHeight > window.innerHeight - 16) top = window.innerHeight - estimatedHeight - 16
  if (top < 16) top = 16
  if (left + width > window.innerWidth - 16) left = window.innerWidth - width - 16
  if (left < 16) left = 16

  return { top, left, width }
}
