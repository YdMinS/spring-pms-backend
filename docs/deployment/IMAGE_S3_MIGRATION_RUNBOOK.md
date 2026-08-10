# IMAGE_S3_MIGRATION_RUNBOOK

로컬 디스크 → S3 상품 이미지 일괄 이전 (one-off). `LocalToS3ImageMigrationRunner` 실행 절차.

## 개요
- 게이트: `IMAGE_MIGRATION_ENABLED=true` 일 때만 부팅 시 1회 실행. 기본 false → 평소 부팅 no-op.
- 이중 가드: `image.storage.type=s3` 아니면 S3 빈 부재 → 러너가 warn 후 no-op.
- 멱등: `imageUrl` 이 이미 `http(s)` 면 skip. 재실행 안전.
- 비파괴: **원본 로컬 파일 미삭제**(검증/롤백용 보존). imageUrl 만 공개 S3 URL 로 재작성.

## 선행 조건
1. `01_BACKEND_S3_STORAGE` 배포 완료 (`type=s3` 정상 동작 — 신규 업로드가 S3 로 감).
2. S3 버킷 / 버킷 정책(퍼블릭 read) / IAM 준비 (PLAN §10). `BucketOwnerEnforced`.
3. 마이그레이션 실행 서버가 **기존 로컬 파일 볼륨**(`image.storage.upload-dir`, 예 `/app/uploads/products`)에 접근 가능.

## 실행 (dev 먼저)
1. dev 앱에 env 설정 후 1회 재기동:
   ```
   IMAGE_MIGRATION_ENABLED=true
   ```
2. 로그 요약 확인:
   ```
   [image-migration] DONE — migrated=N, skippedAlreadyUrl=M, missingFile=0, failed=0
   ```
   - `failed>0` 또는 `missingFile>0` 이면 원인 조사 (파일 경로/권한). 원본은 보존돼 있으므로 재실행 가능.
3. 공개 URL 몇 개를 **익명 GET** → 200 확인 (마켓 봇 접근성).
4. 게이트 off: `IMAGE_MIGRATION_ENABLED` 제거/`false` 로 재기동.

## prod
- dev 검증 후 동일 절차 반복 (prod 버킷/IAM). 게이트 on → 재기동 → 로그 확인 → 게이트 off.

## 검증 쿼리 (완료 기준)
```sql
SELECT count(*) FROM products WHERE image_url IS NOT NULL AND image_url NOT LIKE 'http%';
```
- 결과 **0** 이어야 완료. (테넌트 무관 전체.)

## 롤백
- imageUrl 은 재작성됐지만 원본 로컬 파일은 보존됨 → 문제 시 이전 값 복구 가능 (사전 DB 백업 권장).
- 검증 완료 후 별도 정리 작업에서 로컬 파일 삭제 (이 러너는 삭제하지 않음).

## 구현 참고
- 러너: `com.pms.migration.LocalToS3ImageMigrationRunner`
- 테넌트 순회: `ProductRepository.findDistinctTenantIds()` (native, `@TenantId` 우회 — 별도 Tenant 레지스트리 없음). 테넌트별 `TenantContext.set → finally clear` 후 `findAll` 자동 필터.
- 업로드 훅: `S3ImageStorageService.uploadExisting(Path, contentType)` — 키 = `tenants/{tid}/products/{filename}` (신규 업로드와 동일 규칙).
