# COUPANG_CATEGORY_IMPORT_RUNBOOK

쿠팡 카테고리 xlsx → `PlatformCategory`(쿠팡) 트리 + 수수료 + oclyx 미러 트리 + leaf 매핑 시드 (FEATURE_2608_06 / 53).
**부팅 자동 시더 아님** — 배포 후 운영자가 ADMIN 토큰으로 파일별 1회 수동 호출한다.

## 개요
- 트리거: `POST /api/admin/category-import/coupang` (multipart `file`), ADMIN 전용. 파일(대분류) 1개당 1회 호출.
- 멱등: 재실행 = PlatformCategory 갱신(수수료·이름) + 신규 leaf 만 oclyx 미러/매핑 추가. 기존 oclyx 큐레이션(사용자 이름수정 포함, FK 역조회로 판정) 보존. **재호출 안전.**
- 비파괴: 파괴적 재시드 아님(순수 upsert). 별도 게이트/확인 플래그 없음.
- 수수료 단위: 파일 col B 는 % (예 10.6) → **분수 0.106 으로 저장**(PriceCalculator `1−commission−margin` 단위). ⚠️ `commission_rate` 는 DECIMAL(5,2) 라 0.106 → **0.11 로 반올림 저장**(52 이월 정밀도 이슈).

## 선행 조건
1. **52+53 배포 완료** — changeset `032-platform-category.yaml` 가 대상 DB 에 적용돼 `platform_category` 테이블 + `category_mapping.platform_category_id_fk` 존재. (dev/prod 는 앱 부팅 시 Liquibase 가 자동 적용.)
2. **엑셀 파일 확보** — 사용자 제공 16파일(대분류별), 위치 `~/Downloads/Coupang_Category_20260824_1413/`. 각 파일 = `data` 시트(5행~ leaf, col A `[leafCode] 대>중>...>leaf` + col B 수수료).
3. **ADMIN 토큰** — `POST /api/auth/login` 으로 발급. PlatformCategory 는 `@TenantId` 이므로 **토큰의 tenant 로 시드**된다(현 단일 테넌트=1). oclyx `Category`·`CategoryMapping` 은 tenant 미적용(전역/카테고리 경유). ⚠️ 멀티테넌트 시 테넌트별 토큰으로 각각 import 필요.

## 실행 (dev 먼저)
1. 로그인 → ADMIN 토큰 확보:
   ```bash
   TOKEN=$(curl -s -X POST https://<dev-host>/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"<admin-email>","password":"<pw>"}' | jq -r '.data.token')
   ```
2. 파일별(16회) import — 응답 카운터 확인:
   ```bash
   for f in ~/Downloads/Coupang_Category_20260824_1413/*.xlsx; do
     echo "=== $f ==="
     curl -s -X POST https://<dev-host>/api/admin/category-import/coupang \
       -H "Authorization: Bearer $TOKEN" \
       -F "file=@$f" | jq '.data'
   done
   ```
   각 응답:
   ```json
   { "platformNodesCreated": N, "platformNodesUpdated": 0,
     "oclyxNodesCreated": N, "mappingsCreated": N, "leavesProcessed": N, "skipped": 0 }
   ```
   - 최초 실행: `*Created` > 0, `platformNodesUpdated`/`skipped` = 0.
   - 재실행: `platformNodesUpdated` = leaf 수, `skipped` = leaf 수, `*Created` = 0(신규 파일 아닐 때).
   - 대용량 파일(식품 ~1293 leaf)은 동기 처리라 응답까지 수초 소요 — 프록시 타임아웃 주의(필요 시 타임아웃 상향).
3. 브라우즈 검증: `GET /api/admin/category/tree` (루트) → 대분류 노드 확인, `?parentId=` 로 드릴다운.
4. 관통 확인: 마스터에 leaf 카테고리 지정(`PUT /api/admin/master-products/{id}/category`) → 쿠팡 매핑 존재로 성공 → 가격 산출(regenerate)이 더 이상 400 아님.

## prod
- dev 검증 후 동일 절차(prod host·prod ADMIN 토큰). 멱등이라 재실행 안전.
- ⚠️ **전환기 게이트(52)**: 시드 **전**에는 기존 마스터 가격 산출이 `400`("수수료 미설정 — 카테고리 시드 필요")로 막힘(의도). **배포 → import 시드 사이 공백을 짧게** 가져갈 것.

## 검증 쿼리 (완료 기준)
```sql
-- 쿠팡 leaf(몰코드 보유) 노드 수 — 식품만 ~1293, 16파일 전체면 훨씬 큼
SELECT count(*) FROM platform_category WHERE platform='COUPANG' AND code IS NOT NULL;
-- 중간 노드는 code NULL
SELECT count(*) FROM platform_category WHERE platform='COUPANG' AND code IS NULL;
-- 매핑(oclyx leaf ↔ PlatformCategory FK) 수 = 쿠팡 leaf 수와 일치해야
SELECT count(*) FROM category_mapping WHERE platform='COUPANG' AND platform_category_id_fk IS NOT NULL;
```

## 롤백
- 파괴적 작업 아님(추가/갱신만). 문제 시 사전 DB 백업으로 복구(배포 DB 백업 게이트 = `DB_BACKUP_GATE_RUNBOOK.md`).
- 잘못된 파일을 넣었으면 해당 `platform_category`/`category_mapping`/미러 `category` 행을 수동 정리(자동 정리 API 없음 = 아웃 오브 스코프).

## 구현 참고
- 파서: `com.pms.service.category.CoupangCategoryXlsxParser` (POI `XSSFWorkbook`, `data` 시트 5행~).
- 서비스: `com.pms.service.category.CategoryImportServiceImpl` (파일=단일 트랜잭션, 경로 캐시, leaf 미러 판정 = `CategoryMappingRepository.findByPlatformCategoryId` FK 역조회).
- 컨트롤러: `com.pms.controller.CategoryImportController` (`POST /api/admin/category-import/coupang`).
- 결정 배경: `oklyx-context/decisions/backend/DECISIONS.md` 최근변경(2026-08-25 / 53).
- 후속: 54(프론트 드릴다운) · 네이버 카테고리 import(어댑터 후속).
