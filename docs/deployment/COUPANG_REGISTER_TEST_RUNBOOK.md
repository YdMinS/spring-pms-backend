# 쿠팡 상품등록(register) 실계정 테스트 런북

**목적**: DRAFT 셀을 실제 쿠팡에 등록해 페이로드 형식 적합성을 판정한다. 로컬 테스트로는 절대 판정할 수 없는 것(§1)만 여기서 확인한다.

**대상 기능**: FEATURE_2608_06 상품등록 / 63·73·75·77·93
**최초 작성**: 2026-08-29 (93 배포 직후)
**⚠️ 진행 상태는 §5 체크리스트에 갱신할 것** — 이 문서가 "어디까지 확인됐나"의 단일 진입점이다.

---

## 1. 왜 실계정이어야 하는가

로컬/CI 테스트는 **우리가 만든 JSON** 을 검증할 뿐이다:
- `MockCoupangApiClient` 는 `@Profile("local")` 전용이고 **요청 형식을 검증하지 않는다** — fixture 만 돌려준다.
- 따라서 `./gradlew clean test` 그린 = "우리가 의도한 JSON 을 만들었다"까지. **쿠팡이 그 JSON 을 받아주는지는 별개.**

판정이 실계정에서만 가능한 미해결 항목은 §6.

## 2. 환경

| 항목 | 값 |
|---|---|
| dev 백엔드 컨테이너 | `spring-pms-backend-dev` |
| 프로파일 | `SPRING_PROFILES_ACTIVE=dev` (`deploy-dev.yml:152`) |
| 쿠팡 클라이언트 | `CoupangApiClientImpl` (`@Profile("!local")`) → **실 호출** |
| 호스트 | `https://api-gateway.coupang.com` (하드코딩, `CoupangApiClientImpl:39`) |

🔴 **dev 도 라이브다.** 쿠팡·네이버 상품등록에 **샌드박스가 없다**(`reference_marketplace_api_sandbox`). dev 에서 register 가 성공하면 **실제 쿠팡 계정에 상품이 생긴다.**

**배포본에 대상 커밋이 들어갔는지 확인**(테스트 전 필수):
```bash
docker logs spring-pms-backend-dev 2>&1 | grep -i "Started PmsApplication" | tail -1
# 기동 시각(UTC)이 대상 머지 커밋 시각보다 뒤인지 대조
git log -1 --format='%cd' --date=format-local:'%Y-%m-%d %H:%M:%S UTC' <merge-sha>   # 로컬에서 TZ=UTC
```

**로그 창(등록 누르기 전에 먼저 띄울 것)**:
```bash
docker logs -f --since 1m spring-pms-backend-dev 2>&1 \
  | grep -E "COUPANG|상세 HTML|카테고리 속성|배송설정|필수 카테고리"
```
> `-f` 는 새 줄을 기다린다. 아무것도 안 뜨는 게 정상 — 등록을 눌러야 찍힌다.
> DEBUG 레벨이면 `resp=` 원문까지 나온다(응답 전문이 필요하므로 유지 권장).

## 3. 🔴 등록 성공은 되돌릴 수 없다

register 가 한 번 성공하면 그 셀에 `platformProductId` 가 박히고 **복구 경로가 없다**:

| 잠기는 것 | 근거 |
|---|---|
| DRAFT 복귀 불가 — 재등록은 `"이미 등록됨"` 400 | `ListingRegistrationServiceImpl:57` |
| 그 마스터의 **옵션 이름·수량 변경·삭제 금지** | 84, `MasterProductServiceImpl` |
| **마스터 삭제 409** | `MasterProductServiceImpl:624` |
| 마켓에 올라간 옵션 **체크 해제 금지** | 87, `ListingOptionServiceImpl` |

유일한 탈출구 = `DELETE /api/admin/product-listings/{id}` (마켓 가드 없이 셀 하드 삭제) + **WING 에서 상품 수동 삭제**.

→ **반드시 전용 테스트 마스터를 새로 만들어 시도할 것. 실제 판매 마스터로 첫 등록을 시도하지 말 것.**

## 4. 실행 절차

### 4-1. 전용 테스트 마스터 생성
- 이름: `ZZ <번호> 테스트` 처럼 **식별 가능하게**(정리 대상 구분용)
- 구성상품 1개 + 옵션 1개 → `bundleType=SINGLE` (attributes 검증 경로)
- AB(혼합구성) 도 볼 거면 **구성상품 2개 이상**인 마스터를 하나 더 (§6 의 AB×attributes 판정용)

### 4-2. 등록 전제 5가지
하나라도 비면 **우리 가드가 400 을 내고 쿠팡까지 가지 않는다** = 아무것도 판정 못 함.

| # | 항목 | 위치 |
|---|---|---|
| 1 | accessKey · secretKey · vendorId · **vendorUserId**(WING 로그인 ID) | 판매채널 계정 편집 |
| 2 | 표준 카테고리 지정 + **쿠팡 leaf 매핑** 존재 | 마스터 상세 → 카테고리 |
| 3 | 필수 카테고리 속성 + 고시 값 (**속성 최소 1개** — 93) | 마스터 상세 → 카테고리 메타 |
| 4 | 출고지 · 반품지 선택 완료 | 계정 배송관리 모달 |
| 5 | 마진 프리셋 (판매자 × 플랫폼) | 마진 정책 |

### 4-3. 채널 추가 → 재생성 → 등록
1. 마스터 상세 매트릭스에서 쿠팡 계정 셀 **[채널 추가]**
2. 그 셀 **[재생성]** → 썸네일 S3 URL + 상세 HTML 생성
3. 셀에 **[마켓 등록]** 이 보이고 활성인지 확인
   - 버튼 없음 → 상태가 DRAFT 아님(이미 등록됨)
   - 비활성 → `shippingReady=false`, 툴팁에 사유(→ 4-2 ④)
4. **[마켓 등록]** 클릭 (API: `POST /api/admin/product-listings/{id}/register`)

### 4-4. 결과 판정

**성공**
```
[COUPANG] POST /v2/providers/seller_api/apis/api/v1/marketplace/seller-products q= 1234ms bytes=5678
```
→ 응답의 **sellerProductId 기록** → WING **임시저장함**에서 확인(`requested=false` 라 노출 안 됨) → §4-5 정리

**실패 — 쿠팡이 거부**
```
[COUPANG] POST ... FAIL status=400 resp={...}
```
→ **`resp=` 원문 전체를 §7 에 기록.** 이게 §6 미해결 항목의 유일한 판정 근거다.

**실패 — 우리 가드 (로그에 `[COUPANG] POST` 자체가 없음)**

| 메시지 | 원인 | 조치 |
|---|---|---|
| `이미 등록됨` | 셀이 DRAFT 아님 | 새 셀로 |
| `자동생성 먼저` | `GeneratedProductData` 없음 | [재생성] |
| `상세 HTML 미생성 — 재생성 후 등록하세요` | detailHtml 비어 있음 (93) | [재생성] |
| `활성 옵션 없음` | 활성 옵션 0개 | 옵션 체크 |
| `카테고리 속성 미입력: … — 속성을 1개 이상 입력하세요` | 속성 전부 빔 (93) | 4-2 ③ |
| `필수 카테고리 속성 누락: {옵션}/{속성}` | MANDATORY 속성 빔 | 4-2 ③ |
| `vendorUserId 미설정` | WING 로그인 ID 없음 | 4-2 ① |
| `배송설정 미완료 — 누락 필드: …` | 출고지/반품지 미설정 | 4-2 ④ |
| `배송설정 오류 — 묶음배송…착불` | UNION_DELIVERY + CHARGE_RECEIVED | 배송 설정 수정 |
| `{platform} 카테고리 매핑 미설정` | CategoryMapping 없음 | 4-2 ② |
| `수수료 미설정 — 카테고리 시드 필요` | PlatformCategory.commissionRate null | 카테고리 import(53) |

### 4-5. 정리 (성공했다면 필수)
1. **WING 임시저장함에서 상품 수동 삭제** (API 로는 불가)
2. `DELETE /api/admin/product-listings/{id}` — 셀 하드 삭제
3. 테스트 마스터 삭제 (셀을 먼저 지워야 409 안 남)

---

## 5. 진행 상태

| # | 항목 | 상태 | 일자 | 비고 |
|---|---|---|---|---|
| 1 | dev 배포에 93 포함 | ✅ | 2026-08-29 | 머지 09:40:50 UTC → 기동 09:43:32 UTC |
| 2 | 계정 HMAC 인증 동작 | ✅ | 2026-08-29 | 카테고리 메타 GET 200 (16695 bytes) |
| 3 | 카테고리 메타 조회(47) | ✅ | 2026-08-29 | code=72882. ⚠️ 파싱 결함 발견 → §부록 A / 프롬프트 94 |
| 4 | 전용 테스트 마스터 생성 | ⬜ | | |
| 5 | 등록 전제 5가지 충족 | ⬜ | | |
| 6 | **register 실행 (SINGLE)** | ⬜ | | ← **다음 할 일** |
| 7 | register 실행 (AB) | ⬜ | | §6 ③ 판정용 |
| 8 | WING 임시저장함 확인 | ⬜ | | |
| 9 | 정리 완료 | ⬜ | | |

## 6. 실계정으로만 판정되는 미해결 항목

| # | 항목 | 현재 구현 | 판정 방법 | 결과 시 조치 |
|---|---|---|---|---|
| ① | `contents` 의 `contentsType`/`detailType` | `TEXT` 고정 (93) | 문서 샘플이 TEXT+HTML 조합뿐, HTML 타입 예시 없음 | `contents` 관련 에러 → `CONTENTS_TYPE`·`CONTENT_DETAIL_TYPE` 두 상수만 `HTML` 로 교체 후 재시도 |
| ② | `certifications` | `NOT_REQUIRED` 센티넬 1개 (93) | 실측상 이 카테고리 certifications 는 전부 `required: OPTIONAL` 이고 `NOT_REQUIRED` 가 유효값 | `certification` 에러 → 카테고리 메타 파싱 + 인증코드 입력 UI 후속 프롬프트 |
| ③ | **AB × attributes 충돌** | AB 는 attributes 전면 스킵 (63) | 63 근거("혼합 구성 상품 등록할 때, 속성 입력할 수 없습니다") vs 문서("한개 이상 필수")가 **정면 충돌**. 어느 쪽도 임의로 택하지 않음 | AB 셀 등록 결과로 확정 → 63 또는 93 중 한쪽 수정 |
| ④ | **속성 단위(unit)** | 미전송 (`attributeTypeName` + `attributeValueName` 만) | 필수 속성이 `최소 중량(g)`·`최소 용량(ml)` 등 단위 기반. **값에 단위를 붙이지 말고 숫자만**(`500`) 넣어 시도 | 단위 요구 에러 → `usableUnits` 파싱 + 단위 저장 + payload 동반(94 가 유보한 부분) |
| ⑤ | `groupNumber` 의미 | 파싱 안 함 | `최소 중량`·`최소 용량` 이 둘 다 `groupNumber:"1"` + MANDATORY → "그룹 중 하나만 필수"로 **추정**되나 문서 근거 없음. 일단 둘 다 채워서 통과시킬 것 | 확정되면 필수검증 완화 프롬프트 |
| ⑥ | `delete` = `sales/stop` 엔드포인트 | 문서 불명확, TODO 상태 | 별도 확인 필요 (`CoupangListingAdapter.delete`) | — |

## 7. 실행 기록

> 시도할 때마다 **한 줄씩 append**. 실패 응답은 `resp=` 원문을 그대로.

| 일자 | 마스터/셀 | bundleType | 결과 | 응답 원문 / 비고 |
|---|---|---|---|---|
| 2026-08-29 | — | — | 미실행 | 93 배포 완료, 메타 조회까지 확인 |

---

## 부록 A — 카테고리 메타 실응답 (2026-08-29, code=72882)

`GET /v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/72882` → 200, 16695 bytes. **프롬프트 94 의 근거.**

**attributes[] 실제 키** (파서가 기대하던 `basicUnits[].unit` 은 **존재하지 않음**):
```json
{"attributeTypeName":"최소 중량","dataType":"NUMBER","inputType":"INPUT","inputValues":[],
 "basicUnit":"g","usableUnits":["g","kg","mg"],"required":"MANDATORY","groupNumber":"1","exposed":"EXPOSED"}
{"attributeTypeName":"식품 프리미엄","dataType":"STRING","inputType":"SELECT",
 "inputValues":["Y","해당없음"],"basicUnit":"없음","usableUnits":[],
 "required":"OPTIONAL","groupNumber":"NONE","exposed":"NONE"}
{"attributeTypeName":"동물종류","dataType":"STRING","inputType":"INPUT","inputValues":[],
 "basicUnit":"없음","usableUnits":[],"required":"OPTIONAL","groupNumber":"NONE","exposed":"NONE"}
```
- MANDATORY 4개: `최소 중량`(g, group 1) · `최소 용량`(ml, group 1) · `개당 수량`(개) · `수량`(개)
- 나머지 ~40개는 OPTIONAL, 대부분 `inputType:"SELECT"` + `inputValues[]`
- `basicUnit` 이 없는 속성은 **리터럴 문자열 `"없음"`** (null 아님)

**noticeCategories[]** — 현행 파싱과 **정확히 일치**(무변경):
```json
{"noticeCategoryName":"가공식품","noticeCategoryDetailNames":[
  {"noticeCategoryDetailName":"제품명","required":"MANDATORY"}, ...]}
```
품목군 4개: `농수축산물`(11) · `가공식품`(11) · `건강기능식품`(13) · `기타 재화`(5).
- ⚠️ **전 항목 `MANDATORY`** — 이 카테고리엔 OPTIONAL 고시가 하나도 없다(픽스처에 지어내지 말 것).
- 품목군 간 **고시 key 공유**(`제조연월일, 소비기한 또는 품질유지기한`·`소비자안전을 위한 주의사항`·`소비자상담관련 전화번호`·`포장단위별 내용물의 용량(중량), 수량`) — 91/92 가 다룬 그 구조.
- ⚠️ 기타 재화만 `소비자상담 **관련** 전화번호`(공백 있음)로 **key 가 다르다** — 정규화해서 합치지 말 것.

**certifications[]** — `NOT_REQUIRED` 가 첫 항목이고 이 카테고리는 **전부 `required: OPTIONAL`**:
```json
{"certificationType":"NOT_REQUIRED","name":"***","dataType":"NONE","required":"OPTIONAL"}
```
→ 93 의 `NOT_REQUIRED` 센티넬 선택이 실데이터와 정합(§6 ②).

**미사용 응답 필드**: `requiredDocumentNames[]` · `allowedOfferConditions:["NEW"]` · `isAllowSingleItem:false` · `isExpirationDateRequiredForRocketGrowth:true`.
- ⚠️ `requiredDocumentNames[].required` 는 boolean 이 아니라 **조건 토큰**(`OPTIONAL`·`MANDATORY_PARALLEL_IMPORTED`·`MANDATORY_BATTERY_UN_TEST`·`MANDATORY_BATTERY_MSDS_TEST`·`MANDATORY_INGREDIENTS_PIC`) → 나중에 파싱한다면 `"MANDATORY".equals(...)` 는 전부 false 다.

> **전체 응답 대조 완료(2026-08-29, 94 후속)**: attributes 3종·notices 4그룹·certifications 27종을 실응답 원문과 1:1 대조했다. 파서가 읽는 키(`inputType`/`inputValues`/`basicUnit`/`dataType`)와 `META_FIXTURE_JSON` 이 실응답과 일치한다. 실측 SELECT 속성 예: `보관방식`(냉동/냉장/실온보관)·`구성`(단품/단품세트/혼합세트)·`설탕 함량`·`요거트 종류` 등 **약 30개**. 프론트(95)가 쓸 단위 = `최소 중량` g · `최소 용량` ml · `개당 수량`/`수량` 개, OPTIONAL 페어 `개당 중량`(g)/`개당 용량`(ml) 도 실재.

## 부록 B — 관련 문서

- 프롬프트: `prompts/FEATURE_2608_06_PRODUCT_REGISTRATION/{73,93,94}_*.md`
- 컨텍스트: `oklyx-context/spring-pms-backend.md` 쿠팡 어댑터 섹션 (63·73·75·77·93)
- 스펙 메모리: `reference_coupang_product_creation_api`
- 카테고리 시드: `COUPANG_CATEGORY_IMPORT_RUNBOOK.md`
