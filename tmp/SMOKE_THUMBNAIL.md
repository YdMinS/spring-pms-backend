# SMOKE — 썸네일 전체 플로우 (03_BACKEND_SMOKE_TEST)

- 일시: 2026-08-11
- 브랜치: `feature/thumbnail-generation`
- 기동: `SPRING_PROFILES_ACTIVE=local` + `IMAGE_STORAGE_UPLOADDIR=build/smoke-uploads` (쓰기가능 오버라이드)
- 배너: `ENV: LOCAL (DB=H2, marketplace=MOCK)` + `[LOCAL-SEED]` 3줄 + `Seeded system font: SansSerif` 확인
- 변수: TOKEN(admin) / SELLER_ID=1 / PRODUCT_ID=1("로컬 상품 A") / FONT_ID=1(SansSerif)

## 경로 교정 (Step 0.5)
- 컨트롤러 `@RequestMapping` 실제 대조 — 프롬프트 가정 경로 전부 일치.
- **단, `GET /api/products` 응답은 `.data` 가 아니라 페이지네이션 객체 `.data.content[]`** → 추출 스니펫 교정하여 진행(프롬프트 스니펫의 `.data[]` 는 오류).

## 결과 표

| # | 항목 | 기대 | 결과 |
|---|------|------|------|
| 4-1 | 템플릿 생성 POST | 201, id, active=true | ✅ 201, id=1, active=true |
| 4-2 | 템플릿 GET 왕복 | elements 3개·bind·fontId·region 보존 | ✅ 컨버터+H2 왕복 보존 |
| 4-3 | preview | 200 image/jpeg, 비어있지 않음 | ✅ 1000×1000 JPEG, 23,577 B |
| 4-4 | generate | 200, source=GENERATED, templateId=1, 실파일 | ✅ 디스크 JPEG(ffd8…), 29,342 B (>3KB) |
| 4-5 | 재생성 upsert | 목록 1건 유지, imageUrl(ts) 갱신 | ✅ URL 갱신, count=1 |
| 4-6 | override | 200, MANUAL_OVERRIDE, templateId=null | ✅ |
| 4-7 | 목록 | 1건, sellerName 채워짐 | ✅ sellerName="로컬 테스트 판매자" |
| 4-8 | delete → 목록 | 204 → 빈 배열 | ✅ 204, count=0 |
| ERR | 이미지없음(템플릿 있음) generate | 400 "상품 이미지를 불러올 수 없습니다" | ✅ 400 "…: 이미지가 없습니다" |
| ERR | active 템플릿 없음 generate | 400 "판매자/기본 템플릿이 없습니다" | ✅ 400 |
| ERR | 토큰 없이 generate | 401 | ✅ 401 |

## 실파일 검증 (Step4-#4 핵심)
- `build/smoke-uploads/thumbnails/thumb_1_1_<ts>.jpg` — `file`=JPEG, `xxd` 선두 `ffd8 ffe0`, size 29,342 B, 1000×1000.
- 응답 `imageUrl` 파일명 ts 와 디스크 최신 파일 일치.

## 폰트 가독성 게이트 (Step 5) — 판정: **dev PASS / prod HOLD**
- `resources/fonts/` 에 실 TTF 없음(README만) → `FontRegistry` 가 JDK 논리폰트(SansSerif)로 폴백.
- 이 JVM(macOS) 논리폰트: `Font("SansSerif").canDisplay('한')=true`, `canDisplayUpTo("로컬 상품 A 브랜드")=-1` (전부 표시가능).
- 산출 JPEG 텍스트밴드 육안 확인: "로컬 상품 A" **또렷하게 렌더(두부 아님)**.
- ⚠️ **prod(Linux 컨테이너) HOLD**: 가독성이 macOS 플랫폼의 논리폰트 CJK 폴백에 의존. Linux 논리폰트는 Hangul 글리프가 없어 두부(□) 위험 → 배포 전 OFL CJK TTF(Noto Sans KR/Pretendard)를 `fonts/system-sans.ttf` 로 투입 + `SystemFontSeeder.storageKey` 매칭 필요.

## 버그
- 없음. `01`/`02` 런타임 배선(실렌더→디스크 기록, ProductImageLoader 실로드, 컨버터 H2 왕복, resolveTemplate 실쿼리, upsert 1행) 전부 정상.

## 정리
- bootRun 서버 종료, 포트 8083 해제.
- 임시 산출물(build/smoke-uploads, sample.jpg, preview.jpg)은 build/ 하위 — 필요시 삭제.
