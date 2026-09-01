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
| 4 | 전용 테스트 마스터 생성 | ✅ | 2026-08-30 | master 12 `ZZ 메타확인 테스트`(기타스낵→쿠팡 leaf 72900·구성1·옵션2[1개 활성]) |
| 5 | 등록 전제 5가지 충족 | ✅ | 2026-08-30 | ⚠️ 3개 blocker 우회 필요했음(§6 ⑦⑧ + `vendor_user_id` NULL) |
| 6 | **register 실행 (SINGLE)** | ✅ | 2026-08-30 | **쿠팡 도달·거부**(§7). 상품 미생성 |
| 7 | register 실행 (AB) | ⬜ | | §6 ③ 판정용 — **SINGLE 통과 후** |
| 8 | WING 임시저장함 확인 | — | 2026-08-30 | 상품 미생성이라 해당 없음 |
| 9 | 정리 완료 | — | 2026-08-30 | 상품 미생성이라 해당 없음. 셀 17 은 DRAFT 로 재시도 가능 |
| 10 | **재시도 (고시·배송·단위 3건 수정 후)** | ✅ **등록 성공** | 2026-08-30 | 96 구현 완료(§6 ④⑦⑧⑨⑩ 전부 수정, `feature/register-real-account-fixes`). **dev 배포 후 master 12 / cell 17 로 재시도** → 품목군을 **가공식품**(기타스낵의 올바른 품목군)으로 바꾸고 11개 고시 값 입력 → [마켓 등록] → 결과를 §7 에 append. 결과 = **등록 성공**(`groupNumber` 택1 완화까지 얹은 뒤, §7 마지막 줄). 이로써 §6 ①②⑤⑪ 동반 판정. ⚠️ 성공 응답 원문은 미캡처 |
| 11 | **성공분 정리(§4-5 판매중지)** | ⬜ **미확인** | | #10 이 성공했으므로 **실계정에 상품이 실재한다** — §3 대로 삭제는 불가하고 판매중지가 유일한 정리다. 응답 `sellerProductId` 를 못 남겼으므로 WING 에서 상품을 찾아 확인할 것 |

## 6. 실계정으로만 판정되는 미해결 항목

| # | 항목 | 현재 구현 | 판정 방법 | 결과 시 조치 |
|---|---|---|---|---|
| ① | ✅ **판정 완료(2026-08-30 등록 성공)** — `contents` 의 `contentsType`/`detailType` | `TEXT` 고정 (93). 그대로 통과 | 문서 샘플이 TEXT+HTML 조합뿐, HTML 타입 예시 없음 | `contents` 관련 에러 → `CONTENTS_TYPE`·`CONTENT_DETAIL_TYPE` 두 상수만 `HTML` 로 교체 후 재시도 |
| ② | ✅ **판정 완료(2026-08-30 등록 성공)** — `certifications` | `NOT_REQUIRED` 센티넬 1개 (93). 그대로 통과 — 단 이 카테고리에 한한 결과다(인증이 실제로 필수인 카테고리는 여전히 미판정) | 실측상 이 카테고리 certifications 는 전부 `required: OPTIONAL` 이고 `NOT_REQUIRED` 가 유효값 | `certification` 에러 → 카테고리 메타 파싱 + 인증코드 입력 UI 후속 프롬프트 |
| ③ | **AB × attributes 충돌** | AB 는 attributes 전면 스킵 (63) | 63 근거("혼합 구성 상품 등록할 때, 속성 입력할 수 없습니다") vs 문서("한개 이상 필수")가 **정면 충돌**. 어느 쪽도 임의로 택하지 않음 | AB 셀 등록 결과로 확정 → 63 또는 93 중 한쪽 수정 |
| ④ | **속성 단위(unit)** | ✅ **수정됨(96)** — 어댑터가 전송 시점에 `basicUnit` 부착 | ✅ **2026-08-30 확정 — 공식 문서로 판정 종료** | 🔴 **런북 초기 가설("단위 붙이지 말고 숫자만")이 정반대였다.** [product-creation 문서](https://developers.coupang.com/ko/api/products/product-creation) 원문: `attributeValueName` = "옵션타입명에 해당하는 Value를 **단위와 함께 입력** (예시 `"200ml"`)". **API 에 단위 필드는 없다**(`attributeTypeName`·`attributeValueName`·`exposed` 뿐, 값 30자). WING UI 가 숫자+단위 드롭다운으로 나눠 받고 **합쳐서** 보내는 것. → 어댑터가 `basicUnit` 을 값에 부착(또는 사용자가 단위 포함 입력). 실계정 재시도로 최종 확인 |
| ⑤ | ✅ **해소(2026-08-30)** — `groupNumber` 의미 | `groupNumber` 파싱 + 같은 그룹은 하나만 채워도 충족(해소안 A). 완화 후 재시도가 등록 성공 → 쿠팡도 **택1 로 받는다** | `최소 중량`·`최소 용량` 이 둘 다 `groupNumber:"1"` + MANDATORY → "그룹 중 하나만 필수"로 **추정**되나 문서 근거 없음. 일단 둘 다 채워서 통과시킬 것 | 🔴 **재시도에서 실제로 막힘**: `{"status":"FAILURE","message":"필수 카테고리 속성 누락: 1 / 최소 용량"}` — **쿠팡 응답이 아니라 우리 게이트**(`CoupangListingAdapter:198`). 프론트는 60 설계상 중량/용량을 **택1 페어**로 묶어 한쪽만 채우는데(축 전환 시 반대쪽 clear) 96 게이트는 MANDATORY 속성을 **개별 검사** → 계약 불일치. 쿠팡까지 도달 못 함. 해소안 = (A) `groupNumber` 파싱해 **같은 그룹은 하나만 채워도 충족**(프론트 무변경·권장) / (B) 프론트 페어 해제해 둘 다 입력(고체에 용량을 억지로 채움 = 거짓 데이터, 비권장). A 로 완화해야 **쿠팡이 진짜 판정자**가 되어 ⑤ 를 확정할 수 있다 |
| ⑥ | `delete` = `sales/stop` 엔드포인트 | 문서 불명확, TODO 상태 | 별도 확인 필요 (`CoupangListingAdapter.delete`) | — |
| ⑦ | ✅ **수정됨(96)** — 옵션 수정 500 (실계정 발견 2026-08-30) | `deleteByOptionId` = 벌크 JPQL `@Modifying(flushAutomatically=true)`. ⚠️ `clearAutomatically` 는 **붙이지 않는다**(option 이 detached 되어 LAZY `delivery`/`package_` 접근이 터진다) | `PATCH /api/admin/master-products/{id}/options/{oid}` → `Duplicate entry '16-6' for key 'uq_mpoi_option_product'` | **원인 = Hibernate flush 순서**(파생 삭제는 `em.remove` 뿐 → ActionQueue 가 insert 를 delete 보다 **먼저** 실행). 구성상품이 그대로인 옵션 수정은 **항상** 실패. 수정 = `deleteByOptionId` 를 `@Modifying(flushAutomatically=true, clearAutomatically=true)` 벌크 JPQL 로. 회귀 테스트 = "같은 구성상품으로 옵션 수정 → 성공" |
| ⑧ | ✅ **수정됨(96)** — 무료배송 등록 불가 (실계정 발견 2026-08-30) | `ShippingReadiness.effectiveDeliveryCharge`/`effectiveFreeShipOverAmount`(FREE→0)를 판정·payload 가 공유 + changeset **045** 백필. `freeShipOverAmount` 는 필수 목록에 넣지 않음(CONDITIONAL_FREE 회귀 방지) | 프론트 `ShippingOverrideFields.tsx:436` 이 기본배송비 입력칸을 `NOT_FREE`/`CONDITIONAL_FREE` 일 때만 렌더 → `FREE` 면 `delivery_charge` 가 영원히 null → `shippingReady=false` → [마켓 등록] 영구 비활성 | ① 백엔드에서 `FREE` 면 `deliveryCharge` 를 0 으로 취급(가드 + payload 한 곳에서). ② 기존 행 백필 = **Liquibase 데이터 changeset** `UPDATE ... SET delivery_charge=0 WHERE delivery_charge_type='FREE' AND delivery_charge IS NULL`. ⚠️ **추가로 쿠팡이 `'무료배송을 위한 조건 금액' 값을 확인해 주세요` 를 반환** → `FREE` 에도 `freeShipOverAmount` 가 필요할 수 있음(현재는 `CONDITIONAL_FREE` 전용 optional 취급) |
| ⑨ | 🟡 **백엔드 수정됨(96)·화면 몫은 97** — 필수 고시 누락 (실계정 발견 2026-08-30) | `validateRegistrable` 이 선택 품목군의 `required` 고시를 활성 옵션마다 검사(400, push 미도달). 조기 return 2개(AB / 속성 빈 스키마)를 속성 검증 앞으로 좁혀 고시는 **항상** 검사 | 쿠팡 `'1 번 옵션 의 고시정보' 다시 확인해 주세요` → 저장된 고시 **10개**인데 농수축산물 필수는 **11개**. 누락 = `포장단위별 내용물의 용량(중량),수량,크기` — 이 key 는 `용량/중량/수량` 어절이라 `isOptionNotice` 가 옵션 소유로 넘기고, 옵션 편집기에서도 **"상세입력" 체크를 켜야만** 보인다 | ① 옵션 게이트(`computeMissingOptionRequired`)가 **고시의 `required` 도** 검사하도록. ② 또는 옵션-소유 필수 고시를 기본 노출(상세입력 토글 뒤에 숨기지 않기). ③ 백엔드 `validateRegistrable` 에 고시 필수 검증 추가(현재 속성만 검증 → 쿠팡까지 가서야 알게 됨) |
| ⑩ | ✅ **수정됨(96)** — 고시 품목군 뭉갬 (실측 메타로 발견, 실계정 거부 응답 아님) | detail→group 맵이 `toMap(..., (a,b)->a)` 라 **먼저 온 그룹**이 이김 | 품목군끼리 고시 key 를 공유한다(농수축산물 ↔ 가공식품이 `제조연월일, 소비기한 또는 품질유지기한`·`소비자안전을 위한 주의사항`·`소비자상담관련 전화번호` **3개 공유**) → 선택 그룹이 첫 그룹이 아니면 11개 중 3개가 다른 그룹명으로 전송된다 | 어댑터가 `MasterProduct.categoryNoticeGroup`(91)을 읽어 그 그룹의 고시로만 맵 구성. 저장값 없는 레거시 마스터는 현행 first-wins 유지. ⚠️ 91 의 "이 필드를 어댑터에 배선 금지" 결정을 뒤집은 것 — 그 도출이 공유 key 앞에서 성립하지 않음 |
| ⑪ | ✅ **판정 완료(2026-08-30 등록 성공)** — 계량 고시 값 형식 (`320g 1개`) | 프론트 101 이 `${계량값} ${수량}개` 로 조합(`composeMeasureNotice` 한 곳). 계량값은 물품 `netContent`+단위(`320g`), 수량은 구성상품 수량 합 | 96 ⑨/97 이 **빈 값**을, 101 이 **틀린 값**(수량만 넣던 `1`)을 막았으나 **쿠팡이 이 형식(특히 `개`)을 받는지는 미판정**. `포장단위별 내용물의 용량(중량), 수량` 같은 계량 고시가 대상 | 등록 성공으로 확정 — 쿠팡이 `개` 를 포함한 이 형식을 받는다. 형식 거부 시 **`composeMeasureNotice` 한 함수만** 고친다(리터럴이 그 밖으로 새지 않게 설계됨). ⚠️ 크기까지 요구하는 농수축산물 키(`…용량(중량),수량,크기`)는 조합이 한 조각 비므로 사용자가 덧붙인다 |

## 7. 실행 기록

> 시도할 때마다 **한 줄씩 append**. 실패 응답은 `resp=` 원문을 그대로.

| 일자 | 마스터/셀 | bundleType | 결과 | 응답 원문 / 비고 |
|---|---|---|---|---|
| 2026-08-29 | — | — | 미실행 | 93 배포 완료, 메타 조회까지 확인 |
| 2026-08-30 | master 12 `ZZ 메타확인 테스트` / cell 17 (FeniksKrylo·COUPANG) | SINGLE | **쿠팡 거부 (400 상당)** | `[COUPANG] POST .../seller-products 786ms bytes=158` → `{"code":"ERROR","message":"'1 번 옵션 의 고시정보' 다시 확인해 주세요\|'무료배송을 위한 조건 금액' 값을 확인해 주세요\|유효하지 않은 구매 옵션 값 혹은 단위가 존재합니다.","data":null,"details":null,"errorItems":null}` — `data:null` = **상품 미생성**(WING 정리 불필요). 셀 DRAFT 유지. 우리 앱은 500(`IllegalStateException: 쿠팡 상품등록 응답에 data(sellerProductId) 없음`). 카테고리 = 기타스낵 → 쿠팡 leaf **72900**. 도달 전 3개 blocker 해소: ①옵션 PATCH 500(아래 신규 ⑦) 우회 = DB 직접 수정 ②`shippingReady=false`(아래 신규 ⑧) = `delivery_charge=0` 수동 UPDATE ③`vendor_user_id` NULL = `fenikskrylo` 입력 |

---

| 2026-08-30 07:59 UTC | 재시도(96 배포 후, 프론트 97/101 로컬) | ❌ **우리 게이트에서 차단** | `resp={"status":"FAILURE","message":"필수 카테고리 속성 누락: 1 / 최소 용량","timestamp":"2026-08-30T07:59:19.006145813Z"}` — 쿠팡 미도달. §6 ⑤ 가 실제로 발현(프론트 택1 페어 ↔ 백엔드 개별 MANDATORY 검사). 96 이 막았던 지난 거부 사유(빈 필수 고시·무료배송·품목군)는 재발하지 않음 |

| 2026-08-30 (시각 미기록) | prod 실계정 (listing **39**·35·33 등) | SINGLE | ✅ **prod 첫 등록 성공** (이후 `SELLING`) | 🔴 **응답 원문 미캡처** — 이 줄은 사용자 보고를 2026-09-01 에 소급 기록한 것이다(그때 §7 에 적지 않아 `resp=` 원문·정확한 시각이 남지 않았다). ⚠️ **성공 = 쿠팡 접수**이고 그 앞 시도는 **승인 반려**였다. 반려 사유 2건(우리 코드 버그가 아니라 데이터/규칙): ① 택배사 `LOTTEGLOBAL` 이 그 출고지에 등록된 택배사가 아님(실제는 `HYUNDAI` 계열) ② 무료배송인데 반품배송비 3,500 < 초도배송비 4,000(쿠팡 규칙 = 초도의 100~150%). → 이 둘은 WING 에서 고쳐졌고 **우리 DB 와 어긋난 채로 남아 있다**(104 ③ 이 가리키는 바로 그 값들·Step 4 대상). 성공 자체로 §6 **①②⑤⑪ 가 함께 판정된다**(각 항목의 판정 방법이 "해당 에러가 나느냐"였고, 나지 않았다). ⚠️ **다음 시도부터는 성공이어도 `resp=` 원문을 즉시 이 표에 붙일 것** — 성공 응답의 `sellerProductId` 는 §4-5 정리(판매중지)의 유일한 단서다 |

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
