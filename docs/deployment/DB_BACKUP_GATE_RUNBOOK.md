# DB Pre-Deploy Backup Gate 런북

배포 시 Liquibase 가 적용할 **대기 changeset 이 있을 때만** DB 를 자동 백업하는 게이트.
`deploy.yml`(prod) / `deploy-dev.yml`(dev) 의 SSH 배포 스텝 안, `docker stop` **직전**에 삽입돼 있다.

> **불변식**: `liquibase status` 는 스키마를 **한 글자도 바꾸지 않는다**(읽기 전용). 실제 적용은 앱 부팅 시 자동.

---

## 왜 필요한가
현 릴리스 changeset 은 `dropColumn`/`modifyDataType` 을 포함하며 **rollback 블록이 없다**. Liquibase 자동 롤백 불가 + drop/modify 는 되돌려도 데이터 미복구 → 유일한 안전망은 **마이그레이션 직전 백업**. 매번 수동은 비현실적이라 CI/CD 에서 자동화.

---

## 서버 사전 준비 (수동 1회 — 워크플로가 만들지 않음)
없으면 게이트가 실패한다.

| 항목 | 값/경로 | 비고 |
|---|---|---|
| MySQL 드라이버 jar | `/services/oklyx/mysql-connector-j.jar` | 런북 §10 기준. **크기 2.4M 확인**(잘린 다운로드=드라이버 에러). prod·dev 공용 |
| prod 백업 디렉토리 | `/services/oklyx/db-backups` | 게이트가 `mkdir -p` 하지만 상위 경로 존재 확인 |
| dev 백업 디렉토리 | `/services/oclyx-dev/db-backups` | 상동 |
| liquibase 이미지 | `liquibase/liquibase:4.27` | Spring Boot 3.3.0 관리버전 major 정합 |

⚠️ 드라이버 jar 경로가 다르면 `status` 가 `Cannot find database driver` 로 실패 → **fail-safe 로 백업은 되지만** 대기분 판정은 못 함(항상 백업). 실제 경로 확인 후 yml 의 `DRIVER=` 조정.

---

## 동작 (게이트 로직)
1. scp 스텝이 `src/main/resources/db/**` → 서버 `.../liquibase/db/...` (changelog 는 app.jar 내부라 loose 파일 없음 ⇒ scp 필요).
2. `liquibase status --verbose` 로 대기 changeset 수 조회(읽기 전용).
3. **대기분 > 0 OR status 실패(fail-safe)** → `mysqldump | gzip` 백업.
4. 무결성 3중 검증: 파이프 성공 + `gzip -t` + `Dump completed` 마커. 하나라도 실패 시 **배포 중단**(거짓 안전망 방지).
5. 보관 회전: prod 10개 / dev 3개.
6. 앱 부팅 시 Liquibase 가 실제 changeset 적용 → 백업은 그 직전 상태.

파싱 정규식: `([0-9]+) changesets? (have|has) not been`(대소문자 무시). 4.27 문구 변동 시 실제 서버 출력 1회 확인 후 조정.

---

## 검증 (Definition of Done)
- [ ] **대기분 있음**: 신규 changeset 배포 → 로그 `PENDING=N (>0)` + `$BK_DIR` 에 타임스탬프 `.sql.gz` 생성 + `gzip -t` 통과.
- [ ] **대기분 없음**: changeset 변화 없는 재배포 → 로그 `skip`, 새 백업 없음.
- [ ] **fail-safe**: 드라이버 경로 일부러 틀리게 → `RC!=0` 이어도 게이트 중단 안 됨 + 백업 생성(배포 계속).
- [ ] **무결성**: 파이프 실패/gzip 손상/마커 없음 → `exit 1` 로 배포 중단.
- [ ] **회전**: prod 11번째부터 삭제(10 유지), dev 4번째부터 삭제(3 유지).
- [ ] 앱 부팅 로그: Liquibase 대기분 정상 적용, `validate` 예외 없음.

---

## 복원(restore) 절차 — 운영자 수동 (워크플로 자동 아님)
마이그레이션이 데이터를 깨뜨렸을 때. **prod 예시**(dev 는 컨테이너·경로만 교체).
```bash
# 1) 앱 중단(부팅 시 재적용 방지)
docker stop spring-pms-backend
# 2) 최신(또는 지정) 백업으로 복원 — MYSQL_PWD 로 주입
DUMP=$(ls -1t /services/oklyx/db-backups/pms_db-*.sql.gz | head -1)
gzip -t "$DUMP"   # 복원 전 무결성 재확인
zcat "$DUMP" | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" db-pms \
  sh -c "mysql -u'$DB_USERNAME' pms_db"
# 3) 스키마/데이터 확인 후 앱 재기동
docker start spring-pms-backend
```
⚠️ 복원은 changeset 적용 **이전** 상태로 되돌린다. drop/modify 로 이미 유실된 데이터는 이 백업에만 존재 → 복원 전 `$DUMP` 를 반드시 보존.

---

## 하지 말 것
- ❌ `changelogSync` (baseline 1회 작업, 2026-08-07 완료). 릴리스 changeset 은 실제 테이블 생성이라 sync 시 `validate` 크래시.
- ❌ 워크플로에서 `liquibase update` 직접 호출 — 적용은 앱 부팅에 맡긴다(이중 실행 방지).
- ❌ 평문 비밀번호 로그 노출 / `set -x` — 시크릿 주입만.
- ❌ status 실패 시 백업 스킵 — 반드시 fail-safe.
- ❌ prod/dev 백업 디렉토리 공유.
- ❌ `mysqldump --skip-comments`/`--compact` — `-- Dump completed` 마커가 사라져 무결성 검증 무력화.

---

## 참고
- `PROD_LIQUIBASE_CHANGELOGSYNC_RUNBOOK.md` §10 — liquibase 컨테이너 드라이버 마운트/네트워크/host/changelog 경로 실전 교훈.
- `oklyx-context/decisions/backend/DECISIONS.md` — Liquibase 도입(02).
