<!--
기능 PR(feature/fix → develop)이면 아래 릴리스 섹션을 지우고 무엇을·왜 바꿨는지만 쓰세요.
릴리스 PR(develop → main)이면 그대로 채우세요.

⚠️ 커밋 목록·변경 파일은 Commits / Files changed 탭에 자동으로 붙습니다. 본문에 나열하지 마세요.
   머지된 PR 목록이 필요하면: Releases → Draft a new release → Generate release notes
   또는  git log --merges --pretty='- %s' origin/main..origin/develop
-->

<!-- 한 줄 요약 -->

## Database
<!-- 대기 changeset 범위와 개수 / 없으면 "No changesets."
     백업 게이트가 도는지, 롤백이 단순 재배포로 되는지(스키마가 코드보다 앞서지 않는지) -->

## Deploy order
<!-- 프론트·백엔드 동시 배포가 필요한가. 한쪽만 나가면 무엇이 깨지는가 -->

## Risk
<!-- 되돌릴 수 없는 동작(마켓 등록 등), 외부 API 호출, 설정·환경변수 변경 -->

## Verification
<!-- 배포 후 무엇을 눌러 확인하는가 -->
