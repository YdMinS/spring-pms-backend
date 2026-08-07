# OKLYX — 로컬 개발 & 브랜치/환경 전략

인프라 산출물 소유 레포 = **spring-pms-backend**. Makefile / `.env.example` 이 여기 있음.

## 로컬 기동 (Makefile)

전제: `spring-pms-backend/` 와 `nextjs-oklyx-front/` 가 형제 디렉토리(oklyx/ 아래 나란히).

| 명령 | 동작 |
|---|---|
| `make up` | 백엔드(local, H2) + 프론트(next dev) 동시 기동. Ctrl-C 또는 `make down` 으로 정리 |
| `make be-local` | 백엔드만 (`SPRING_PROFILES_ACTIVE=local`, H2, env 불필요) |
| `make fe-local` | 프론트만 (`npm run dev`) |
| `make be-mysql` | MySQL 동등성 로컬 검증 (기존 `docker-compose.yml`, 선택 — `.env` 필요) |
| `make test-be` | 백엔드 per-class 테스트 (기본 CarrierRateServiceTest; `TEST_CLASS=` 로 변경) |
| `make down` | `make up`/compose 로 띄운 프로세스 정리 |

- `make up` 은 `local` 프로파일 → H2 인메모리 + 더미 secret(`application-local.yml`) → **env 주입 없이 즉시 부팅**.
- 프론트는 로컬 백엔드(`:8083`)로 요청. 백엔드 Swagger: http://localhost:8083/swagger-ui.html
- `.run/` (pidfile) 은 gitignore. compose 는 `docker compose down` 으로 별도 정리.
- ⚠️ 전체 `./gradlew test` 는 H2 공유DB 이슈로 flaky → `make test-be` 는 per-class 로만 게이트(전체 안정화는 후속 TODO).

## 환경 ↔ 브랜치 매핑

환경(LOCAL/DEV/PROD)에 1:1 매핑. 풀 GitFlow 아님(작은 팀 → 가벼운 3종).

| 브랜치 | 배포 | 역할 | 프로파일 |
|---|---|---|---|
| `main` | PROD (api.oclyx.com) | 검증된 것만. 보호: PR + CI 초록 | `prod` |
| `develop` | DEV/스테이징 | 통합·검증 | `dev` |
| `feature/*` | (LOCAL) | 개별 작업, 짧게 유지·자주 머지 | `local` |

- 흐름: `feature/*` → `develop` → `main`.
- **LOCAL 은 브랜치 아님** = 런타임 프로파일(`local`). 어느 브랜치든 `make up` 으로 로컬 기동.
- `release/*` `hotfix/*` 는 지금 도입 X — 급한 운영 수정만 `main` 에서 `hotfix/*` 따서 main+develop 반영.
- ⚠️ **장수 메가 브랜치 금지**: 대공사는 짧은 feature 브랜치로 쪼개 자주 머지(코드 포크 함정 회피).
- oklyx 는 메타 디렉토리 → backend/frontend/mobile 각 레포에 동일 브랜치 전략.
  단 **배포(CD)는 be/fe 만** — mobile 은 앱이라 이 스택 배포 대상 아님.
- ⚠️ git commit/push 는 사용자 전담(Claude 는 확인·안내만).

## 배포 env / secret

- 런타임 secret(`SPRING_PROFILES_ACTIVE / DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET / OKLYX_CRYPTO_MASTER_KEY`)
  키 목록 = `.env.example`. 실제 값은 **서버에만**(git 제외).
- 누락 시 `StartupEnvironmentValidator`(fail-fast) 가 부팅 중단(`__UNSET__` 감지).
- DEV↔PROD **DB·쿠팡계정·도메인 완전 분리**.

## CD (raw docker run, 환경별 분리)

배포는 GitHub Actions 가 담당 — **raw `docker run`** 방식(ghcr 이미지 pull → run, compose 아님).
ghcr 이미지: `ghcr.io/ydmins/spring-pms-backend`, `ghcr.io/ydmins/nextjs-oklyx-front`.
네트워크: `pms_network` + `ydmins_proxy_network`(nginx 리버스 프록시). PROD·DEV 는 **컨테이너 이름·호스트포트·nginx vhost** 로 완전 분리.

| 트리거 | 워크플로 | 태그 | 컨테이너 | 호스트포트(be/fe) | 도메인 | 런타임 secret |
|---|---|---|---|---|---|---|
| `main` push | `deploy.yml` | `:latest` | `spring-pms-backend` / `nextjs-oklyx-front` | 8083 / 3000 | api.oclyx.com / oclyx.com | GitHub Secrets(`-e`) |
| `develop` push | `deploy-dev.yml` | `:dev` | `spring-pms-backend-dev` / `nextjs-oklyx-front-dev` | 8084 / 3001 | api-dev.oclyx.com / dev.oclyx.com | GitHub Secrets `DEV_*`(`-e`) |

- DEV backend 런타임 secret 은 PROD와 동일하게 **GitHub Secrets(`-e`)** 로 주입(서버 `.env` 파일 안 씀). `SPRING_PROFILES_ACTIVE=dev`, `DB_URL` 은 비밀 아님 → 워크플로에 하드코딩.
- `db-pms`(PROD) / `db-pms-dev`(DEV) 는 배포마다 recreate 하지 않음(persistent) — 앱 컨테이너만 교체.

### DEV 활성화에 필요한 수동 작업(1회) → 상세: `DEV_SETUP.md`
- 서버: `db-pms-dev`(mysql:8.0, pms_network, named volume) · `/services/oclyx-dev/uploads/products` · nginx `api-dev.*`/`dev.*` vhost+인증서.
- GitHub Secrets 신규: backend `DEV_DB_USERNAME`/`DEV_DB_PASSWORD`/`DEV_JWT_SECRET`/`DEV_OKLYX_CRYPTO_MASTER_KEY`, frontend `API_URL_DEV`. 나머지(SERVER_HOST/USER/SSH_PRIVATE_KEY/TAILSCALE_AUTH_KEY/CR_PAT/GITHUB_TOKEN)는 기존 재사용.
- frontend 레포 `develop` 브랜치 생성(현재 main 만 존재).

> 미적용(별도 결정): CI 게이트(test/lint/build), compose 오케스트레이션 전환, PROD 버전핀.
