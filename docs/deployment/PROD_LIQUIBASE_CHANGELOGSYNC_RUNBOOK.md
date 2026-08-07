# Prod Liquibase `changelogSync` 배포 런북

> **성격**: 운영 절차(런북). 코드가 아니다. 기존 데이터가 있는 실 DB(prod, 기존 dev)에 Liquibase 도입 빌드를 **처음** 배포할 때 **최초 1회** 따른다. 이후 새 환경 온보딩 시 재사용.
>
> **불변식**: `changelogSync` 는 스키마를 **한 글자도 바꾸지 않는다**. `DATABASECHANGELOG` 에 baseline 을 "적용됨"으로 기록만 추가한다.
>
> **주체**: 실제 명령은 **사용자/배포 파이프라인**이 prod 자격증명으로 실행한다. 자동화 도구가 prod 자격증명으로 직접 실행하지 않는다.

관련 설계: `oklyx-context/decisions/backend/DECISIONS.md` (Liquibase 도입 02) · 프롬프트 `prompts/FEATURE_2608_02_DB_MIGRATION_FOUNDATION/03_BACKEND_PROD_CHANGELOGSYNC.md`

---

## 0. 왜 필요한가

Liquibase 도입 빌드는 dev/prod 에서 `spring.liquibase.enabled=true` + `ddl-auto: validate` 로 뜬다. 그러나 prod DB 엔 **이미 18개 테이블·데이터가 존재**한다. Liquibase 가 부팅 시 `001-baseline` 을 그냥 실행하면 `Table already exists` → **앱 기동 실패**.

그래서 baseline 을 **실행하지 않고** `DATABASECHANGELOG` 에 "적용됨"으로 기록만 남기는 `changelogSync` 를 **최초 1회** 돌린다. 이후 배포부터는 앱 부팅 시 Liquibase 가 **`002` 이후 신규 changeset 만** 자동 적용한다.

---

## 1. 적용 대상 판별 (실행 전 필수 분기)

| DB 상태 | 조치 | 이유 |
|---------|------|------|
| **기존 데이터 有** (현 prod, 기존 dev) | **`changelogSync` 1회** | baseline 실행 시 "already exists" → 실패. sync 로 회피 |
| **빈 신규 DB** (새로 만든 dev/스테이징) | **아무것도 안 함** | 부팅 시 baseline 이 정상 실행돼 스키마 생성 |

⚠️ **판별 실수 = 사고.**
- 대상 DB 에 이미 `member`/`seller` 등 테이블이 있으면 → "기존 데이터 有".
- 빈 신규 DB 에 sync 하면 baseline 이 "적용됨"으로 **잘못 마킹**돼 실제 테이블이 안 생김 → 부팅 시 `validate` 실패.

---

## 2. 실행 순서 (배포 게이트)

> 아래는 **하나의 배포 창(deployment window)** 안에서 순서대로. **sync 가 앱 부팅보다 반드시 먼저.**

| # | 단계 | 확인/명령 |
|---|------|-----------|
| 1 | **선행 아티팩트 확인** | 배포 아티팩트에 `db/changelog/db.changelog-master.yaml` + `changes/001-baseline.yaml` 포함 확인 |
| 2 | **prod DB 백업** | 스냅샷/덤프 확보 (롤백 근거) |
| 3 | **현재 상태 확인** | 대상 DB 에 `DATABASECHANGELOG` 테이블이 **없어야** 정상(최초). 있으면 이미 sync 됨 → §5 |
| 4 | **(권장) SQL 미리보기** | `changelogSyncSQL` 로 실행될 SQL 출력·검토 (§3) |
| 5 | **`changelogSync` 1회 실행** | §3 명령. prod 자격증명 |
| 6 | **검증** | §4 체크리스트 (`001-baseline` 행 1개, 스키마 diff 0) |
| 7 | **그다음 앱 배포** | Liquibase 도입 빌드를 prod 에 배포 → 부팅 → "001 이미 적용됨" 확인, 변경 없이 통과 → `validate` 통과 → 정상 기동 |

**순서 위반 시**: 5 없이 7 부터 하면 → 부팅 시 baseline 실행 시도 → `Table already exists` → 기동 실패. **반드시 5 → 7.**

---

## 3. 실행 명령 (사용자/파이프라인 실행)

자격증명은 환경변수/시크릿으로 주입한다. **명령에 평문 비밀번호를 남기지 말 것.**

```bash
# 4. (권장) 먼저 실행될 SQL 을 출력해 검토 — DATABASECHANGELOG INSERT 만 있어야 정상
liquibase \
  --changeLogFile=db/changelog/db.changelog-master.yaml \
  --url="$DB_URL" \
  --username="$DB_USERNAME" \
  --password="$DB_PASSWORD" \
  changelogSyncSQL

# 5. 최초 1회 baseline 을 "적용됨"으로 마킹 (스키마 변경 없음)
liquibase \
  --changeLogFile=db/changelog/db.changelog-master.yaml \
  --url="$DB_URL" \
  --username="$DB_USERNAME" \
  --password="$DB_PASSWORD" \
  changelogSync
```

- `--changeLogFile` 경로 = 아티팩트 내 master 위치(빌드/실행 방식에 맞게 조정).
- **CLI 버전 정합**: 앱은 `org.liquibase:liquibase-core` 를 **Spring Boot 3.3.0 관리 버전(4.27.x)** 으로 사용. CLI 도 major 4.x 로 맞춘다.
- `changelogSyncSQL` 출력에 `CREATE TABLE`/`ALTER` 가 보이면 **중단** — 뭔가 잘못된 것(대상 DB 판별 재확인).

---

## 4. 검증 (Definition of Done)

- [ ] `DATABASECHANGELOG` 테이블 생성됨, `001-baseline*` changeset 행이 **전부** `EXECTYPE=EXECUTED` 로 기록됨.
- [ ] sync 전후 **스키마 diff 0** (테이블·컬럼·제약 변화 없음). `changelogSync` 는 기록만 하므로 당연하되, 확인은 필수.
- [ ] 앱 부팅 로그: Liquibase 가 신규 적용 changeset **0개**로 통과, `ddl-auto: validate` 예외 없음.
- [ ] 앱 정상 기동, actuator 헬스체크 200.

> 참고: baseline 은 다중 changeSet(테이블별, id 접두어 `001-baseline-*`) 구조이므로 `DATABASECHANGELOG` 에 행이 여러 개 기록된다(단일 1행 아님).

---

## 5. 재실행 / 멱등성

- `changelogSync` 는 **최초 1회만**. 이미 `DATABASECHANGELOG` 에 `001-baseline*` 이 있으면 재실행 불필요(다시 돌려도 마킹된 changeset 은 건너뜀).
- **빈 신규 DB 에는 절대 sync 하지 말 것** — baseline 이 "적용됨"으로 잘못 마킹돼 실제 테이블이 안 생김 → 부팅 시 `validate` 실패.

---

## 6. 롤백 (sync 자체는 스키마 무변경이라 위험 낮음)

- sync 만 하고 배포 안 한 상태로 되돌리려면: `DATABASECHANGELOG` 에서 해당 행 제거 또는 테이블 drop(스키마 원본은 그대로).
- 배포까지 갔다가 앱이 안 뜨면: 이전 빌드로 롤백 + §2-2 백업 근거로 상태 확인. baseline 은 실행 안 됐으므로 스키마 손상 없음.

---

## 7. 하지 말 것

- ❌ 기존 prod 에 baseline **실행**(`update`/부팅 자동적용) — 반드시 `changelogSync`.
- ❌ 빈 신규 DB 에 `changelogSync`(테이블이 안 생김).
- ❌ 자동화 도구가 prod 자격증명으로 직접 실행 — 사용자/파이프라인 전담.
- ❌ sync 없이 Liquibase 빌드를 prod 부팅(기동 실패).
- ❌ 명령에 평문 비밀번호 하드코딩.

---

## 8. dev MySQL 사전 검증 (배포 전 강력 권장)

prod 에 sync 하기 전, **빈 dev MySQL 에 baseline 을 실제로 한 번 실행(`update`)** 해 통째로 적용됨을 확인한다. 특히 이식형 `CLOB` 로 통일한 대용량 텍스트 2컬럼(`products.description`, `order_item.raw`)이 MySQL(LONGTEXT)에서 정상 생성되는지 확인. (근거: DECISIONS 02 — H2 `validate` 는 이 2컬럼에서 divergence, 이식성 우선으로 CI 는 apply-check 로 고정.)

---

## 9. 참고

- `src/main/resources/db/changelog/db.changelog-master.yaml`, `changes/001-baseline.yaml` — sync 대상 changelog.
- `src/main/resources/application-prod.yml` — `spring.liquibase.enabled=true`, `ddl-auto: validate` (해당 블록 주석이 이 런북을 참조).
- `oklyx-context/decisions/backend/DECISIONS.md` (Liquibase 도입 02) — 상위 설계.
- 향후 새 환경 온보딩 시 §1 판별표부터 다시 적용.
