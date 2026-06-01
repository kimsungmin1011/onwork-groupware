-- OnWork 시드 데이터 — [SW아키텍처] 2차 발표 '가상 조직도' 기준 (대표 22명)
-- 개발/데모용. 비밀번호는 전원 'onwork1234!' (bcrypt, pgcrypto crypt()).
SET client_encoding = 'UTF8';
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 근무그룹 (기본 9-to-6)
INSERT INTO work_groups (id, group_name, work_start_time, work_end_time, is_default)
VALUES (1, '기본 9-to-6', '09:00', '18:00', TRUE);

-- 부서 (manager_id는 users 생성 후 UPDATE)
INSERT INTO departments (id, name, status) VALUES
  (1, '경영지원팀', 'ACTIVE'),
  (2, '개발팀',     'ACTIVE'),
  (3, '영업팀',     'ACTIVE'),
  (4, '기획팀',     'ACTIVE');

-- 사용자 (id, 부서, RBAC role, 직급, 사번, 이름, email, 입사일)
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

-- 팀장 지정
UPDATE departments SET manager_id = 3  WHERE id = 1;  -- 경영지원팀: 박지수
UPDATE departments SET manager_id = 5  WHERE id = 2;  -- 개발팀: 최현준
UPDATE departments SET manager_id = 11 WHERE id = 3;  -- 영업팀: 정수연
UPDATE departments SET manager_id = 17 WHERE id = 4;  -- 기획팀: 한소희

-- 인증정보 (전원 'onwork1234!', bcrypt)
INSERT INTO user_credentials (user_id, password_hash)
SELECT id, crypt('onwork1234!', gen_salt('bf')) FROM users;

-- 휴가 유형
INSERT INTO leave_types (id, code, name, days_unit, is_active) VALUES
  (1, 'ANNUAL',  '연차',      1.0, TRUE),
  (2, 'COMP',    '보상휴가',  1.0, TRUE),
  (3, 'HALF_AM', '오전반차',  0.5, TRUE),
  (4, 'HALF_PM', '오후반차',  0.5, TRUE);

-- 전사 설정 (싱글톤 id=1)
INSERT INTO attendance_settings (id, grace_in_minutes, grace_out_minutes, late_threshold_count, is_overtime_auto_collect, updated_by)
VALUES (1, 10, 10, 3, FALSE, 1);
INSERT INTO leave_settings (id, annual_rollover, comp_expire_warning_days) VALUES (1, FALSE, 7);

-- 팀별 승인자/대행자 (팀장 1차 → 대행자, ADR-LVE-001)
-- 대행자는 경영지원팀장(박지수, id 3). 경영지원팀은 다른 HR(송미래, id 4)이 대행.
-- 팀장·대행자 모두 부재 시 경영진(CEO/VP)으로 자동 에스컬레이션(서비스 로직).
INSERT INTO leave_approvers (department_id, approver_id, delegate_id, is_absent) VALUES
  (1, 3,  4,  FALSE),
  (2, 5,  3,  FALSE),
  (3, 11, 3,  FALSE),
  (4, 17, 3,  FALSE);

-- 연차 잔여(샘플): 전 직원 ANNUAL 20일, 올해 귀속
INSERT INTO leave_balances (user_id, leave_type_id, total_days, used_days, year)
SELECT id, 1, 20.0, 0.0, 2026 FROM users WHERE status = 'ACTIVE';

-- IDENTITY 시퀀스를 시드 최대 id 다음으로 재정렬 (이후 앱 INSERT 충돌 방지)
SELECT setval(pg_get_serial_sequence('work_groups','id'),    (SELECT MAX(id) FROM work_groups));
SELECT setval(pg_get_serial_sequence('departments','id'),    (SELECT MAX(id) FROM departments));
SELECT setval(pg_get_serial_sequence('users','id'),          (SELECT MAX(id) FROM users));
SELECT setval(pg_get_serial_sequence('leave_types','id'),    (SELECT MAX(id) FROM leave_types));
SELECT setval(pg_get_serial_sequence('attendance_settings','id'), (SELECT MAX(id) FROM attendance_settings));
SELECT setval(pg_get_serial_sequence('leave_settings','id'), (SELECT MAX(id) FROM leave_settings));
