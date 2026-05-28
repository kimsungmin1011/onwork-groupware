# OnWork 배포 가이드 — Vercel + Render + Neon + Upstash

> 프론트는 Vercel(정적), 백엔드는 Render(JVM), DB는 Neon Postgres, Redis는 Upstash. 모두 무료 티어 가능.
> 코드는 이미 배포 준비 완료(환경변수화, Dockerfile, vercel.json, init.sql). 아래는 **계정 가입 + 대시보드 설정**만 따라하면 됩니다.

## 0. 사전: GitHub 레포 push 상태 확인
```
git status                                 # 워킹트리 클린
git log --oneline -5                       # 최신 커밋 확인
git push origin main                       # 원격 동기화 (필수)
```

## 1. Neon (PostgreSQL 16 — 무료, 0.5GB)
1. https://neon.tech 회원가입 (GitHub OAuth 가장 빠름)
2. New Project → 이름 `onwork`, Region `Singapore` 또는 `Tokyo`
3. 생성 후 Dashboard → **Connection string** 복사 (`postgres://user:pass@ep-xxxx.aws.neon.tech/onwork?sslmode=require`)
4. **SQL Editor** 탭 열기 → `db/schema.sql` 내용 전체 붙여넣기 → Run
5. 같은 SQL Editor에 `db/seed.sql` 붙여넣기 → Run
6. `db/demo_seed.sql` 붙여넣기 → Run (시연 데이터)
7. (확인) `SELECT count(*) FROM users;` → 22 나오면 OK

## 2. Upstash (Redis — 무료, 256MB)
1. https://upstash.com 회원가입 (GitHub OAuth)
2. Console → Create Database → 이름 `onwork-redis`, Region `ap-northeast-1` (Tokyo)
3. 생성 후 **Endpoint / Port / Password** 메모
4. TLS는 기본 활성화 (REDIS_SSL=true 필요)

## 3. Render (Spring Boot 백엔드 — 무료, 750h/month)
1. https://render.com 회원가입 (GitHub OAuth)
2. New → **Web Service** → "Build and deploy from a Git repository"
3. 본 레포(`onwork-groupware`) 연결 → Permissions: All repositories or Only select
4. 설정:
   - **Name**: `onwork-backend`
   - **Region**: Singapore
   - **Branch**: `main`
   - **Root Directory**: `backend`
   - **Runtime**: Docker (Dockerfile 자동 감지)
   - **Instance Type**: Free
5. **Environment Variables** (Settings → Environment) 다음 입력:
   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://ep-xxxx.aws.neon.tech/onwork?sslmode=require
   SPRING_DATASOURCE_USERNAME=<neon-user>
   SPRING_DATASOURCE_PASSWORD=<neon-pass>
   SPRING_DATA_REDIS_HOST=<upstash-endpoint>
   SPRING_DATA_REDIS_PORT=<upstash-port>
   SPRING_DATA_REDIS_PASSWORD=<upstash-password>
   SPRING_DATA_REDIS_SSL_ENABLED=true
   ONWORK_JWT_SECRET=<256bit-이상의-랜덤-문자열 (openssl rand -base64 48)>
   ONWORK_CORS_ORIGINS=https://onwork-xxxx.vercel.app
   ```
   > `ONWORK_CORS_ORIGINS`는 4단계에서 Vercel 도메인 확정 후 다시 들어와 수정 (저장 시 자동 재배포).
6. **Create Web Service** 클릭 → 빌드 5~7분
7. 배포 완료 후 `https://onwork-backend.onrender.com/api/v1/ping` 접속 → `{"status":"ok"}` 확인

> 💡 Render Free는 15분 미요청 시 슬립. 첫 요청 시 30초 대기. 시연 전에 미리 한 번 ping.

## 4. Vercel (React 프론트엔드 — 무료)
1. https://vercel.com 회원가입 (GitHub OAuth)
2. New Project → 본 레포 import → Permissions: 비공개 레포 접근 허용
3. 설정:
   - **Framework Preset**: Vite (자동 감지)
   - **Root Directory**: `frontend`
   - **Build Command**: `npm run build` (자동)
   - **Output Directory**: `dist` (자동)
4. **Environment Variables**:
   ```
   VITE_API_BASE=https://onwork-backend.onrender.com/api/v1
   ```
5. **Deploy** → 1~2분
6. 완료 후 도메인 표시 (예: `https://onwork-groupware-xxxx.vercel.app`)
7. **3단계로 돌아가서** `ONWORK_CORS_ORIGINS`를 위 도메인으로 업데이트 → Render 자동 재배포

## 5. 동작 확인
- Vercel URL 접속 → 로그인 화면 → `daehan@onwork.kr` / `onwork1234!`
- 대시보드 위젯에 값이 보이면 백엔드 연결 OK
- 첫 로그인은 Render 슬립 깨우느라 30초 정도 걸릴 수 있음

## 6. 시연 종료 후 (선택)
- Render: Settings → Suspend (요금 발생 방지)
- Vercel: 대시보드 → Project Settings → Delete
- Neon/Upstash: 그대로 둬도 무료, 또는 삭제

## 비용
모두 무료 티어로 한 학기 시연 충분. 다만 Render Free는 15분 슬립이라 응답 첫 호출이 느림. 프로젝트로 진지하게 운영하면 Render Starter ($7/월)로 항상 켜둘 수 있음.

## 트러블슈팅
- **CORS 에러**: `ONWORK_CORS_ORIGINS`에 정확한 Vercel 도메인이 들어갔는지 확인 (https://, 끝 슬래시 없음)
- **DB 연결 실패**: `SPRING_DATASOURCE_URL`이 `jdbc:postgresql://` 프리픽스 + `?sslmode=require` 들어갔는지 확인
- **Redis 연결 실패**: Upstash는 TLS 필수 → `SPRING_DATA_REDIS_SSL_ENABLED=true`
- **첫 로그인 30초+**: Render Free 슬립 워밍업, 2회차부터 정상
- **로그 보기**: Render 대시보드 → Logs 탭 / Vercel 대시보드 → Deployments → Functions Logs
