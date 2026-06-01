-- OnWork 0601 추가 시드: 일정(오늘의 일정) · 급여(마이페이지) · 6월 근태(이번 달 요약)
-- 멱등(재실행 안전): 기존 행은 건너뜀. 적용: psql <url> -f db/seed_0601.sql

-- ============================================================ 1) 오늘의 일정 (사원당 3일치)
-- 이미 일정이 있으면 중복 삽입 방지
DELETE FROM schedules WHERE user_id IN (SELECT id FROM users WHERE status = 'ACTIVE');
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

-- ============================================================ 2) 급여 (마이페이지 전용)
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

-- ============================================================ 3) 이번 달(6월) 근태 — 요약 카드용
-- 평일(월~금) 09:00~18:00 정상 출근 기록. 기존 기록은 유지.
INSERT INTO daily_work_records (user_id, date, clock_in_at, clock_out_at, overtime_minutes, status)
SELECT u.id, g.d::date, g.d + TIME '09:00', g.d + TIME '18:00', 0, 'NORMAL'
FROM users u
CROSS JOIN generate_series(DATE '2026-06-01', DATE '2026-06-26', INTERVAL '1 day') AS g(d)
WHERE u.status = 'ACTIVE'
  AND EXTRACT(ISODOW FROM g.d) < 6
ON CONFLICT (user_id, date) DO NOTHING;

-- 지각 1회: 6/10 09:14 출근 + LATE 이상
UPDATE daily_work_records
SET clock_in_at = DATE '2026-06-10' + TIME '09:14', status = 'ANOMALY'
WHERE date = DATE '2026-06-10'
  AND user_id IN (SELECT id FROM users WHERE status = 'ACTIVE');
INSERT INTO work_anomalies (daily_work_record_id, anomaly_type)
SELECT r.id, 'LATE'
FROM daily_work_records r
WHERE r.date = DATE '2026-06-10'
  AND r.user_id IN (SELECT id FROM users WHERE status = 'ACTIVE')
  AND NOT EXISTS (
        SELECT 1 FROM work_anomalies a WHERE a.daily_work_record_id = r.id AND a.anomaly_type = 'LATE');

-- 초과 근무 합계 3h 20m (200분): 6/17 +120분, 6/24 +80분
UPDATE daily_work_records SET overtime_minutes = 120, clock_out_at = DATE '2026-06-17' + TIME '20:00'
WHERE date = DATE '2026-06-17' AND user_id IN (SELECT id FROM users WHERE status = 'ACTIVE');
UPDATE daily_work_records SET overtime_minutes = 80, clock_out_at = DATE '2026-06-24' + TIME '19:20'
WHERE date = DATE '2026-06-24' AND user_id IN (SELECT id FROM users WHERE status = 'ACTIVE');
