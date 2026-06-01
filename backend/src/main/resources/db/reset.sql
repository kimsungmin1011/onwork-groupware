-- OnWork 시연용 초기화 스크립트 (DemoResetService가 트랜잭션으로 실행).
-- 정책(2026-06-01): 출퇴근·결재·일반 휴가신청 더미는 두지 않는다(깨끗한 상태에서 라이브 시연).
--   유지: 직원/부서/인증, 연차 잔여(20일), 오늘의 일정, 급여.
--   시연 세팅: 개발팀장(최현준)·영업팀장(정수연)만 오늘 휴가 → 두 팀 사원 휴가신청이
--             대행자 경영지원팀장(박지수)에게 라우팅되는 흐름을 바로 시연.
-- 순수 SQL만 사용(psql 메타명령 금지). 비밀번호는 전원 'onwork1234!'.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================ 0) 전체 초기화
TRUNCATE TABLE
  employee_change_histories, leave_grants, leave_histories, leave_requests, leave_balances,
  leave_approvers, leave_settings, leave_types,
  approvals, hr_change_requests, notifications,
  overtime_requests, work_anomalies, monthly_summaries, daily_work_records, attendance_settings,
  schedules, salaries, onboarding_tutorial_progress,
  user_credentials, users, departments, work_groups
  RESTART IDENTITY CASCADE;

-- ============================================================ 1) 마스터
INSERT INTO work_groups (id, group_name, work_start_time, work_end_time, is_default)
VALUES (1, '기본 9-to-6', '09:00', '18:00', TRUE);

INSERT INTO departments (id, name, status) VALUES
  (1, '경영지원팀', 'ACTIVE'),
  (2, '개발팀',     'ACTIVE'),
  (3, '영업팀',     'ACTIVE'),
  (4, '기획팀',     'ACTIVE');

INSERT INTO users (id, department_id, work_group_id, employee_no, name, email, role, position, status, hire_date) VALUES
  (1,  NULL, 1, '2020-001', '김대한', 'daehan@onwork.kr', 'CEO',        '대표이사',  'ACTIVE', '2020-01-02'),
  (2,  NULL, 1, '2020-002', '이민국', 'minguk@onwork.kr', 'VP',         '부대표이사','ACTIVE', '2020-01-02'),
  (3,  1,    1, '2020-003', '박지수', 'jisoo@onwork.kr',  'HR_MANAGER', '팀장',      'ACTIVE', '2020-02-01'),
  (4,  1,    1, '2023-010', '송미래', 'mirae@onwork.kr',  'HR_MANAGER', '사원',      'ACTIVE', '2023-03-02'),
  (5,  2,    1, '2020-004', '최현준', 'hyunjun@onwork.kr','MANAGER',    '차장',      'ACTIVE', '2020-03-01'),
  (6,  2,    1, '2021-005', '강태양', 'taeyang@onwork.kr','EMPLOYEE',   '과장',      'ACTIVE', '2021-04-01'),
  (7,  2,    1, '2022-006', '오다연', 'dayeon@onwork.kr', 'EMPLOYEE',   '대리',      'ACTIVE', '2022-05-02'),
  (8,  2,    1, '2023-007', '윤성호', 'seongho@onwork.kr','EMPLOYEE',   '주임',      'ACTIVE', '2023-06-01'),
  (9,  2,    1, '2024-008', '류하은', 'haeun@onwork.kr',  'EMPLOYEE',   '사원',      'ACTIVE', '2024-01-02'),
  (10, 2,    1, '2025-009', '임준서', 'junseo@onwork.kr', 'EMPLOYEE',   '사원',      'ACTIVE', '2025-03-02'),
  (11, 3,    1, '2020-010', '정수연', 'suyeon@onwork.kr', 'MANAGER',    '차장',      'ACTIVE', '2020-03-01'),
  (12, 3,    1, '2021-011', '백민준', 'minjun@onwork.kr', 'EMPLOYEE',   '과장',      'ACTIVE', '2021-04-01'),
  (13, 3,    1, '2022-012', '홍나리', 'nari@onwork.kr',   'EMPLOYEE',   '대리',      'ACTIVE', '2022-05-02'),
  (14, 3,    1, '2023-013', '전지후', 'jihoo@onwork.kr',  'EMPLOYEE',   '주임',      'ACTIVE', '2023-06-01'),
  (15, 3,    1, '2024-014', '신유진', 'yujin@onwork.kr',  'EMPLOYEE',   '사원',      'ACTIVE', '2024-01-02'),
  (16, 3,    1, '2025-015', '문도현', 'dohyun@onwork.kr', 'EMPLOYEE',   '사원',      'ACTIVE', '2025-03-02'),
  (17, 4,    1, '2020-016', '한소희', 'sohee@onwork.kr',  'MANAGER',    '차장',      'ACTIVE', '2020-03-01'),
  (18, 4,    1, '2021-017', '장하늘', 'haneul@onwork.kr', 'EMPLOYEE',   '과장',      'ACTIVE', '2021-04-01'),
  (19, 4,    1, '2022-018', '이보람', 'boram@onwork.kr',  'EMPLOYEE',   '대리',      'ACTIVE', '2022-05-02'),
  (20, 4,    1, '2023-019', '채원우', 'wonwoo@onwork.kr', 'EMPLOYEE',   '주임',      'ACTIVE', '2023-06-01'),
  (21, 4,    1, '2024-020', '고은서', 'eunseo@onwork.kr', 'EMPLOYEE',   '사원',      'ACTIVE', '2024-01-02'),
  (22, 4,    1, '2025-021', '남지원', 'jiwon@onwork.kr',  'EMPLOYEE',   '사원',      'ACTIVE', '2025-03-02');

UPDATE departments SET manager_id = 3  WHERE id = 1;
UPDATE departments SET manager_id = 5  WHERE id = 2;
UPDATE departments SET manager_id = 11 WHERE id = 3;
UPDATE departments SET manager_id = 17 WHERE id = 4;

INSERT INTO user_credentials (user_id, password_hash)
SELECT id, crypt('onwork1234!', gen_salt('bf')) FROM users;

INSERT INTO leave_types (id, code, name, days_unit, is_active) VALUES
  (1, 'ANNUAL',  '연차',      1.0, TRUE),
  (2, 'COMP',    '보상휴가',  1.0, TRUE),
  (3, 'HALF_AM', '오전반차',  0.5, TRUE),
  (4, 'HALF_PM', '오후반차',  0.5, TRUE);

INSERT INTO attendance_settings (id, grace_in_minutes, grace_out_minutes, late_threshold_count, is_overtime_auto_collect, updated_by)
VALUES (1, 10, 10, 3, FALSE, 1);
INSERT INTO leave_settings (id, annual_rollover, comp_expire_warning_days) VALUES (1, FALSE, 7);

INSERT INTO leave_approvers (department_id, approver_id, delegate_id, is_absent) VALUES
  (1, 3,  4,  FALSE),
  (2, 5,  3,  FALSE),
  (3, 11, 3,  FALSE),
  (4, 17, 3,  FALSE);

INSERT INTO leave_balances (user_id, leave_type_id, total_days, used_days, year)
SELECT id, 1, 20.0, 0.0, 2026 FROM users WHERE status = 'ACTIVE';

SELECT setval(pg_get_serial_sequence('work_groups','id'),    (SELECT MAX(id) FROM work_groups));
SELECT setval(pg_get_serial_sequence('departments','id'),    (SELECT MAX(id) FROM departments));
SELECT setval(pg_get_serial_sequence('users','id'),          (SELECT MAX(id) FROM users));
SELECT setval(pg_get_serial_sequence('leave_types','id'),    (SELECT MAX(id) FROM leave_types));

-- ============================================================ 2) 마이페이지/일정 더미 (근태·결재·휴가는 두지 않음)
-- 오늘의 일정(사원당 3일치) — 회의 일정. 근태 기록과 무관.
INSERT INTO schedules (user_id, date, start_time, end_time, title, kind)
SELECT u.id, CURRENT_DATE + t.day_offset, t.start_time, t.end_time, t.title, t.kind
FROM users u
CROSS JOIN (VALUES
    (0, TIME '09:30', TIME '10:00', '팀 데일리 스탠드업', 'MEETING'),
    (0, TIME '15:00', TIME '16:00', '업무 협의', 'MEETING'),
    (1, TIME '11:00', TIME '12:00', '1:1 면담', 'MEETING'),
    (2, TIME '14:00', TIME '15:30', '월간 업무 리뷰', 'MEETING')
) AS t(day_offset, start_time, end_time, title, kind)
WHERE u.status = 'ACTIVE';

-- 급여(마이페이지 전용)
INSERT INTO salaries (user_id, base_pay, meal_allowance, transport_allowance, position_allowance, pay_day)
SELECT u.id,
       CASE u.role
            WHEN 'CEO' THEN 8000000 WHEN 'VP' THEN 6500000
            WHEN 'HR_MANAGER' THEN 5000000 WHEN 'MANAGER' THEN 4500000
            ELSE 3200000 END,
       200000, 100000,
       CASE u.role
            WHEN 'CEO' THEN 1000000 WHEN 'VP' THEN 700000
            WHEN 'HR_MANAGER' THEN 300000 WHEN 'MANAGER' THEN 300000
            ELSE 0 END,
       25
FROM users u
WHERE u.status = 'ACTIVE'
ON CONFLICT (user_id) DO NOTHING;

-- ============================================================ 3) 시연 시나리오: 개발팀장·영업팀장 오늘 휴가
-- 최현준(5, 개발팀장) / 정수연(11, 영업팀장)을 오늘부터 승인 휴가 → 두 팀 사원의 휴가 신청이
-- 대행자(경영지원팀장 박지수, id 3)에게 라우팅되는 것을 시연. (그 외 출퇴근/결재/휴가 데이터는 없음.)
INSERT INTO leave_requests (user_id, leave_balance_id, start_date, end_date, days_used, reason, status, approver_id, approved_at)
SELECT 5,  id, CURRENT_DATE, CURRENT_DATE + 2, 3.0, '연차 (시연용 — 팀장 부재)', 'APPROVED', 1, NOW()
  FROM leave_balances WHERE user_id=5  AND leave_type_id=1 AND year=2026;
INSERT INTO leave_requests (user_id, leave_balance_id, start_date, end_date, days_used, reason, status, approver_id, approved_at)
SELECT 11, id, CURRENT_DATE, CURRENT_DATE + 2, 3.0, '연차 (시연용 — 팀장 부재)', 'APPROVED', 1, NOW()
  FROM leave_balances WHERE user_id=11 AND leave_type_id=1 AND year=2026;

UPDATE leave_balances SET used_days = used_days + 3.0 WHERE user_id IN (5, 11) AND leave_type_id=1 AND year=2026;
