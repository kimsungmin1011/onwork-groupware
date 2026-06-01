import { useCallback, useEffect, useMemo, useState } from 'react'
import AppLayout from '../components/AppLayout'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { hrSurfaceLabel, isExecutive } from '../lib/roles'

interface Employee {
  id: number
  employeeNo: string
  name: string
  email: string
  role: string
  position: string | null
  status: string
  departmentName: string | null
}

interface ChangeRequest {
  id: number
  changeType: string
  status: string
  reason: string | null
  payload: Record<string, unknown>
  requestedBy: number
  batchId: string | null
}

interface Department {
  id: number
  name: string
}

interface Salary {
  basePay: number
  mealAllowance: number
  transportAllowance: number
  positionAllowance: number
  gross: number
  estimatedNet: number
  payDay: number
}

const STATUS_LABEL: Record<string, string> = { ACTIVE: '재직', INACTIVE: '비활성', RESIGNED: '퇴사' }

const EMPTY_FORM = {
  name: '',
  email: '',
  hireDate: '',
  departmentId: '',
  position: '',
  workGroupId: '1',
}

export default function HrPage() {
  const { user } = useAuth()
  const isExec = isExecutive(user?.role)
  const isHrManager = user?.role === 'HR_MANAGER'
  const isManager = user?.role === 'MANAGER'
  const isEmployee = user?.role === 'EMPLOYEE'
  const canSeeRequests = isExec || isHrManager

  const [employees, setEmployees] = useState<Employee[]>([])
  const [selectedEmployee, setSelectedEmployee] = useState<Employee | null>(null)
  const [selectedRequest, setSelectedRequest] = useState<ChangeRequest | null>(null)
  const [requests, setRequests] = useState<ChangeRequest[]>([])   // PENDING
  const [selectedRequestIds, setSelectedRequestIds] = useState<Set<number>>(new Set())
  const [batchResult, setBatchResult] = useState<string | null>(null)
  const [departments, setDepartments] = useState<Department[]>([])
  const [salary, setSalary] = useState<Salary | null>(null)
  const [loading, setLoading] = useState(true)
  const [employeeQuery, setEmployeeQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ ...EMPTY_FORM })
  const [suggestedNo, setSuggestedNo] = useState('')
  const [errors, setErrors] = useState<Record<string, boolean>>({})
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const emp = await api.get<{ items: Employee[] }>('/hr/employees')
      setEmployees(emp.data.items)
      if (canSeeRequests) {
        const reqs = await api.get<{ items: ChangeRequest[] }>('/hr/change-requests', isExec
          ? { params: { status: 'PENDING' } }
          : undefined)
        setRequests(reqs.data.items)
      }
      if (isEmployee) {
        // 급여는 마이페이지(본인)에서만 조회
        const sal = await api.get<Salary>('/hr/salary/me').catch(() => null)
        setSalary(sal?.data ?? null)
      }
    } finally {
      setLoading(false)
    }
  }, [canSeeRequests, isExec, isEmployee])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  async function ensureFormDeps() {
    if (departments.length === 0) {
      const d = await api.get<{ items: Department[] }>('/hr/departments')
      setDepartments(d.data.items)
    }
    const sno = await api.get<{ employeeNo: string }>('/hr/change-requests/next-employee-no')
    setSuggestedNo(sno.data.employeeNo)
  }

  async function openNewForm() {
    setShowForm(true)
    setForm({ ...EMPTY_FORM })
    setErrors({})
    await ensureFormDeps()
  }

  function closeForm() {
    setShowForm(false)
    setErrors({})
  }

  function buildPayload(): Record<string, unknown> {
    return {
      name: form.name.trim(),
      email: form.email.trim(),
      hire_date: form.hireDate || null,
      department_id: form.departmentId ? Number(form.departmentId) : null,
      position: form.position.trim() || null,
      role: 'EMPLOYEE',
      work_group_id: Number(form.workGroupId) || 1,
    }
  }

  function validateForSubmit(): boolean {
    const errs: Record<string, boolean> = {}
    if (!form.name.trim()) errs.name = true
    if (!form.email.trim()) errs.email = true
    if (!form.hireDate) errs.hireDate = true
    setErrors(errs)
    return Object.keys(errs).length === 0
  }

  async function onSubmitForApproval() {
    if (submitting) return
    if (!validateForSubmit()) {
      alert('필수 정보를 입력하세요 (이름·이메일·입사일)')
      return
    }
    // UC-HR-01 정상 흐름 6/7단계: 확인 모달 — 취소 시 폼 입력 보존 (A3)
    const ok = window.confirm(`${form.name} 님의 입사 등록을 경영진에게 승인 요청하시겠습니까?`)
    if (!ok) return
    setSubmitting(true)
    try {
      await api.post('/hr/change-requests', {
        changeType: 'CREATE',
        payload: buildPayload(),
        reason: '신규 입사 등록',
      })
      closeForm()
      await load()
      alert('승인 요청이 등록되었습니다')
    } catch (err) {
      alert((err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? '요청 실패')
    } finally {
      setSubmitting(false)
    }
  }

  async function process(id: number, action: 'APPROVE' | 'REJECT') {
    let reason: string | null = null
    if (action === 'REJECT') {
      reason = window.prompt('반려 사유를 입력하세요')
      if (!reason) return
    }
    await api.patch(`/hr/change-requests/${id}/process`, { action, reason })
    setSelectedRequest(null)
    await load()
  }

  function toggleRequest(id: number) {
    const next = new Set(selectedRequestIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelectedRequestIds(next)
  }

  async function batchApprove() {
    const ids = [...selectedRequestIds].slice(0, 50)
    if (ids.length === 0) return
    const ok = window.confirm(`${ids.length}건을 일괄 승인하시겠습니까?`)
    if (!ok) return
    const { data } = await api.post<{
      batchId: string
      total: number
      successCount: number
      failureCount: number
    }>('/hr/change-requests/batch-process', { ids, action: 'APPROVE' })
    setBatchResult(`묶음 번호 ${data.batchId} · 성공 ${data.successCount} / 실패 ${data.failureCount} / 전체 ${data.total}`)
    setSelectedRequestIds(new Set())
    await load()
  }

  const pageTitle = isEmployee ? '마이페이지' : isManager ? '팀원 정보' : '인사관리'
  const pageSub = isEmployee
    ? '내 인사 정보와 계정 정보를 확인합니다.'
    : isManager
      ? '내 부서 구성원의 기본 정보를 확인합니다.'
      : '직원 정보와 인사 변경 요청을 관리합니다.'
  const profile = employees[0] ?? null
  const requestSectionTitle = isExec ? '인사 결재함' : '내 인사 요청'
  const employeeListTitle = isManager ? '팀원 목록' : '직원 목록'
  const activeEmployees = employees.filter((item) => item.status === 'ACTIVE').length
  const filteredEmployees = useMemo(() => {
    const query = employeeQuery.trim().toLowerCase()
    return employees.filter((item) => {
      const matchesQuery = !query
        || item.name.toLowerCase().includes(query)
        || item.email.toLowerCase().includes(query)
        || item.employeeNo.toLowerCase().includes(query)
        || (item.departmentName ?? '').toLowerCase().includes(query)
      const matchesStatus = statusFilter === 'ALL' || item.status === statusFilter
      return matchesQuery && matchesStatus
    })
  }, [employeeQuery, employees, statusFilter])

  return (
    <AppLayout>
      <div className="work-screen">
        <section className="screen-hero hr-hero">
          <div>
            <p className="screen-kicker">{hrSurfaceLabel(user?.role)} 워크스페이스</p>
            <h1>{pageTitle}</h1>
            <p>{pageSub}</p>
          </div>
          <div className="hr-signal-card">
            <span>조직 데이터</span>
            <strong>{isEmployee ? profile?.employeeNo ?? '-' : `${activeEmployees}명`}</strong>
            <small>{isEmployee ? profile?.departmentName ?? '부서 미지정' : `전체 ${employees.length}명 중 재직`}</small>
          </div>
        </section>

        <section className="metric-grid">
          <article className="metric-card tone-blue">
            <span className="metric-label">{isEmployee ? '내 상태' : '전체 구성원'}</span>
            <strong className="metric-value">{isEmployee ? STATUS_LABEL[profile?.status ?? ''] ?? '-' : employees.length}</strong>
            <span className="metric-hint">{isEmployee ? profile?.position ?? '-' : '조회 가능 인원'}</span>
          </article>
          <article className="metric-card tone-green">
            <span className="metric-label">재직</span>
            <strong className="metric-value">{isEmployee ? profile?.departmentName ?? '-' : activeEmployees}</strong>
            <span className="metric-hint">{isEmployee ? '소속 부서' : '재직 중'}</span>
          </article>
          <article className="metric-card tone-amber">
            <span className="metric-label">{canSeeRequests ? '인사 요청' : '화면 구분'}</span>
            <strong className="metric-value">{canSeeRequests ? requests.length : hrSurfaceLabel(user?.role)}</strong>
            <span className="metric-hint">{isExec ? '결재 대기' : isHrManager ? '등록/변경 요청' : '권한 기준'}</span>
          </article>
          {!isEmployee && (
            <article className="metric-card tone-slate">
              <span className="metric-label">필터 결과</span>
              <strong className="metric-value">{filteredEmployees.length}</strong>
              <span className="metric-hint">현재 목록</span>
            </article>
          )}
        </section>

      {isEmployee && profile && (
        <section className="card-block service-panel" data-testid="my-profile">
          <div className="panel-heading">
            <div>
              <h2>내 정보</h2>
              <p>계정과 조직 기준 정보입니다.</p>
            </div>
          </div>
          <dl className="detail-list profile-list">
            <div><dt>이름</dt><dd>{profile.name}</dd></div>
            <div><dt>사번</dt><dd>{profile.employeeNo}</dd></div>
            <div><dt>부서</dt><dd>{profile.departmentName ?? '미분류'}</dd></div>
            <div><dt>직급</dt><dd>{profile.position ?? '-'}</dd></div>
            <div><dt>상태</dt><dd>{STATUS_LABEL[profile.status] ?? profile.status}</dd></div>
            <div className="detail-wide"><dt>이메일</dt><dd>{profile.email}</dd></div>
          </dl>
        </section>
      )}

      {isEmployee && salary && (
        <section className="card-block service-panel" data-testid="my-salary">
          <div className="panel-heading">
            <div>
              <h2>급여 정보</h2>
              <p>마이페이지에서만 확인할 수 있는 개인 급여 명세입니다. (매월 {salary.payDay}일 지급)</p>
            </div>
          </div>
          <dl className="detail-list profile-list">
            <div><dt>기본급</dt><dd>{salary.basePay.toLocaleString()}원</dd></div>
            <div><dt>식대</dt><dd>{salary.mealAllowance.toLocaleString()}원</dd></div>
            <div><dt>교통비</dt><dd>{salary.transportAllowance.toLocaleString()}원</dd></div>
            <div><dt>직책수당</dt><dd>{salary.positionAllowance.toLocaleString()}원</dd></div>
            <div><dt>세전 합계</dt><dd><strong>{salary.gross.toLocaleString()}원</strong></dd></div>
            <div><dt>실수령액(추정)</dt><dd><strong>{salary.estimatedNet.toLocaleString()}원</strong></dd></div>
          </dl>
          <p className="muted small">※ 실수령액은 4대보험·소득세 약 9% 공제를 가정한 추정치입니다.</p>
        </section>
      )}

      {canSeeRequests && (
        <section className="card-block service-panel" data-testid="hr-inbox">
          <div className="approvals-header">
            <div>
              <h2 className="section-title">{requestSectionTitle}</h2>
              <p className="muted small">행을 클릭하면 변경 필드와 묶음 처리 번호를 확인할 수 있습니다.</p>
            </div>
            {isExec && (
              <div className="batch-actions">
                <span className="count-chip">선택 {selectedRequestIds.size}건</span>
                <button className="btn-sm primary" disabled={selectedRequestIds.size === 0} onClick={batchApprove}>
                  선택 일괄 승인
                </button>
              </div>
            )}
          </div>
          {batchResult && <div className="toast-line">{batchResult}</div>}
          {requests.length === 0 ? (
            <div className="empty-state">{isExec ? '대기 중인 인사 변경 요청이 없습니다.' : '내가 등록한 인사 요청이 없습니다.'}</div>
          ) : (
            <div className="table-shell">
              <table className="data-table">
                <thead><tr>{isExec && <th style={{ width: 32 }}>선택</th>}<th>유형</th><th>상태</th><th>대상/내용</th><th>사유</th><th>묶음 번호</th>{isExec && <th>처리</th>}</tr></thead>
                <tbody>
                  {requests.map((r) => (
                    <tr key={r.id} className="clickable-row" onClick={() => setSelectedRequest(r)}>
                      {isExec && (
                        <td onClick={(event) => event.stopPropagation()}>
                          <input
                            type="checkbox"
                            checked={selectedRequestIds.has(r.id)}
                            onChange={() => toggleRequest(r.id)}
                            aria-label={`인사 요청 ${r.id} 선택`}
                          />
                        </td>
                      )}
                      <td><span className="type-chip blue">{r.changeType}</span></td>
                      <td><span className="badge inactive">{r.status}</span></td>
                      <td><strong>{(r.payload?.name as string) ?? `user#${r.payload?.targetUserId ?? '-'}`}</strong></td>
                      <td>{r.reason ?? '-'}</td>
                      <td>{r.batchId ?? '-'}</td>
                      {isExec && (
                        <td className="actions" onClick={(event) => event.stopPropagation()}>
                          <button className="btn-sm primary" onClick={() => process(r.id, 'APPROVE')}>승인</button>
                          <button className="btn-sm" onClick={() => process(r.id, 'REJECT')}>반려</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {(isHrManager || isExec) && (
        <section className="card-block service-panel" data-testid="hire-section">
          <div className="approvals-header">
            <div>
              <h2 className="section-title">신규 입사 등록</h2>
              <p className="muted small">{isExec ? '신규 입사 요청을 등록합니다. 경영진 결재함에서 승인 시 계정이 생성됩니다.' : '임시저장 없이 PENDING 요청으로 경영진에게 전달됩니다.'}</p>
            </div>
            {!showForm && (
              <button className="btn-primary" onClick={openNewForm} data-testid="open-hire-form">
                신규 입사자 등록
              </button>
            )}
          </div>

          {showForm && (
            <div className="hire-form elevated-form" data-testid="hire-form">
              <p className="muted small">
                신규 입사자 등록 · 제출 즉시 경영진 결재함에 표시됩니다.
              </p>
              <div className="hire-grid">
                <label>
                  <span>이름 *</span>
                  <input className={errors.name ? 'invalid' : ''} value={form.name}
                         onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="홍길동" />
                </label>
                <label>
                  <span>사번 (자동 채번)</span>
                  <input value={suggestedNo} readOnly />
                </label>
                <label>
                  <span>이메일 *</span>
                  <input type="email" className={errors.email ? 'invalid' : ''} value={form.email}
                         onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="name@onwork.kr" />
                </label>
                <label>
                  <span>입사일 *</span>
                  <input type="date" className={errors.hireDate ? 'invalid' : ''} value={form.hireDate}
                         onChange={(e) => setForm({ ...form, hireDate: e.target.value })} />
                </label>
                <label>
                  <span>부서</span>
                  <select value={form.departmentId}
                          onChange={(e) => setForm({ ...form, departmentId: e.target.value })}>
                    <option value="">미분류 (승인 후 분류 가능)</option>
                    {departments.map((d) => (
                      <option key={d.id} value={d.id}>{d.name}</option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>직급</span>
                  <input value={form.position}
                         onChange={(e) => setForm({ ...form, position: e.target.value })} placeholder="사원" />
                </label>
              </div>
              <div className="hire-actions">
                <button className="btn-ghost" onClick={closeForm} disabled={submitting}>닫기</button>
                <button className="btn-primary" onClick={onSubmitForApproval} disabled={submitting} data-testid="submit-approval">
                  승인 요청
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      {!isEmployee && (
        <section className="card-block service-panel">
          <div className="approvals-header">
            <div>
              <h2 className="section-title">{employeeListTitle}</h2>
              <p className="muted small">직원 행을 클릭하면 상세 정보를 확인합니다.</p>
            </div>
            <span className="count-chip">{filteredEmployees.length} / {employees.length}</span>
          </div>
          <div className="toolbar-row">
            <input value={employeeQuery} onChange={(e) => setEmployeeQuery(e.target.value)} placeholder="이름, 사번, 이메일, 부서 검색" />
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="ALL">전체 상태</option>
              <option value="ACTIVE">재직</option>
              <option value="INACTIVE">비활성</option>
              <option value="RESIGNED">퇴사</option>
            </select>
          </div>
          {loading ? (
            <div className="skeleton-stack"><span /><span /><span /></div>
          ) : filteredEmployees.length === 0 ? (
            <div className="empty-state">조건에 맞는 직원이 없습니다.</div>
          ) : (
            <div className="table-shell">
              <table className="data-table" data-testid="employee-table">
                <thead><tr><th>사번</th><th>이름</th><th>부서</th><th>직급</th><th>권한</th><th>상태</th></tr></thead>
                <tbody>
                  {filteredEmployees.map((e) => (
                    <tr key={e.id} className="clickable-row" onClick={() => setSelectedEmployee(e)}>
                      <td>{e.employeeNo}</td>
                      <td><strong>{e.name}</strong><span className="table-sub">{e.email}</span></td>
                      <td>{e.departmentName ?? '미분류'}</td>
                      <td>{e.position ?? '-'}</td>
                      <td><span className="type-chip slate">{e.role}</span></td>
                      <td>
                        <span className={'badge ' + e.status.toLowerCase()}>
                          {STATUS_LABEL[e.status] ?? e.status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      </div>

      {selectedEmployee && (
        <div className="modal-backdrop" role="presentation" onClick={() => setSelectedEmployee(null)}>
          <section className="detail-drawer" role="dialog" aria-modal="true" aria-label="직원 상세" onClick={(event) => event.stopPropagation()}>
            <div className="detail-header">
              <div>
                <p className="muted small">직원 상세</p>
                <h2 className="section-title">{selectedEmployee.name}</h2>
              </div>
              <button className="btn-ghost" onClick={() => setSelectedEmployee(null)}>닫기</button>
            </div>
            <dl className="detail-list">
              <div><dt>사번</dt><dd>{selectedEmployee.employeeNo}</dd></div>
              <div><dt>상태</dt><dd>{STATUS_LABEL[selectedEmployee.status] ?? selectedEmployee.status}</dd></div>
              <div><dt>부서</dt><dd>{selectedEmployee.departmentName ?? '미분류'}</dd></div>
              <div><dt>직급</dt><dd>{selectedEmployee.position ?? '-'}</dd></div>
              <div><dt>권한</dt><dd>{selectedEmployee.role}</dd></div>
              <div><dt>화면 구분</dt><dd>{hrSurfaceLabel(user?.role)}</dd></div>
              <div className="detail-wide"><dt>이메일</dt><dd>{selectedEmployee.email}</dd></div>
            </dl>
          </section>
        </div>
      )}

      {selectedRequest && (
        <div className="modal-backdrop" role="presentation" onClick={() => setSelectedRequest(null)}>
          <section className="detail-drawer" role="dialog" aria-modal="true" aria-label="인사 요청 상세" onClick={(event) => event.stopPropagation()}>
            <div className="detail-header">
              <div>
                <p className="muted small">인사 요청 상세</p>
                <h2 className="section-title">{selectedRequest.changeType}</h2>
              </div>
              <button className="btn-ghost" onClick={() => setSelectedRequest(null)}>닫기</button>
            </div>
            <dl className="detail-list">
              <div><dt>요청 ID</dt><dd>{selectedRequest.id}</dd></div>
              <div><dt>상태</dt><dd>{selectedRequest.status}</dd></div>
              <div><dt>요청자 ID</dt><dd>{selectedRequest.requestedBy}</dd></div>
              <div><dt>batch_id</dt><dd>{selectedRequest.batchId ?? '-'}</dd></div>
              <div className="detail-wide"><dt>사유</dt><dd>{selectedRequest.reason ?? '-'}</dd></div>
            </dl>
            <table className="data-table diff-table">
              <thead><tr><th>필드</th><th>변경 전</th><th>변경 후</th></tr></thead>
              <tbody>
                {Object.entries(selectedRequest.payload ?? {}).map(([field, value]) => (
                  <tr key={field}>
                    <td>{field}</td>
                    <td className="muted">승인 전 원장 미반영</td>
                    <td>{String(value ?? '-')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {isExec && (
              <div className="detail-actions">
                <button className="btn-sm primary" onClick={() => process(selectedRequest.id, 'APPROVE')}>승인</button>
                <button className="btn-sm" onClick={() => process(selectedRequest.id, 'REJECT')}>반려</button>
              </div>
            )}
          </section>
        </div>
      )}
    </AppLayout>
  )
}
