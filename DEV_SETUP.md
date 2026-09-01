# DEV 환경 활성화 런북 (develop → dev.oclyx.com / api-dev.oclyx.com)

`deploy-dev.yml`(be/fe)은 `develop` push 시 `:dev` 이미지를 빌드→서버에 배포한다.
그 배포가 실제로 뜨려면 아래를 **순서대로 1회** 준비한다. (앱 컨테이너는 워크플로가 자동으로 만든다.
아래는 워크플로가 만들지 않는 사전 조건이다.)

- 배포 방식 = PROD와 동일한 raw `docker run`. 런타임 secret = **GitHub Secrets(`-e`)** 로 주입(서버 `.env` 파일 안 씀).
- PROD와 DEV는 컨테이너 이름·호스트포트·nginx 도메인으로 완전 분리(같은 서버 공존).

| | PROD(기존) | DEV(신규) |
|---|---|---|
| backend 컨테이너 | `spring-pms-backend` (8083) | `spring-pms-backend-dev` (호스트 8084→8083) |
| frontend 컨테이너 | `nextjs-oklyx-front` (3000) | `nextjs-oklyx-front-dev` (호스트 3001→3000) |
| DB | `db-pms` | `db-pms-dev` |
| 도메인 | api.oclyx.com / oclyx.com | api-dev.oclyx.com / dev.oclyx.com |

---

## STEP 1 — DNS

DNS 제공자에서 A 레코드 2개 추가. IP = **PROD와 같은 서버 공인 IP**.

```
dev.oclyx.com       A   <서버 IP>
api-dev.oclyx.com   A   <서버 IP>
```

확인: `dig +short dev.oclyx.com` 가 서버 IP를 반환하면 OK. (와일드카드 `*.oclyx.com` 이 있으면 이 단계 생략.)

---

## STEP 2 — 서버에서 DEV DB 컨테이너 만들기

서버에 SSH 접속 후, 아래를 그대로 실행. `<...>` 3곳만 원하는 값으로 채운다.

```bash
docker network inspect pms_network >/dev/null 2>&1 || docker network create pms_network
docker volume create db_pms_dev

docker run -d --name db-pms-dev \
  --network pms_network \
  -e MYSQL_ROOT_PASSWORD='<루트비번>' \
  -e MYSQL_DATABASE=pms_db \
  -e MYSQL_USER='<앱DB유저>' \
  -e MYSQL_PASSWORD='<앱DB비번>' \
  -v db_pms_dev:/var/lib/mysql \
  --restart unless-stopped \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

- 볼륨명 `db_pms_dev` 는 임의(원하는 이름 가능). `create` 와 `-v` 의 이름만 일치하면 됨.

- 여기서 정한 `<앱DB유저>` / `<앱DB비번>` 은 **STEP 4의 `DEV_DB_USERNAME` / `DEV_DB_PASSWORD` 와 같은 값**을 쓴다.
- 호스트 포트를 열지 않음(내부 `pms_network` 로만 접근). 직접 접속이 필요하면 위에 `-p 127.0.0.1:3308:3306` 추가.
- 확인: `docker ps | grep db-pms-dev` 가 `running` 이면 OK.
- ⚠️ PROD `db-pms` 와 다른 컨테이너·볼륨. DEV DB는 비어서 시작 → 앱이 `ddl-auto: update` 로 스키마 자동 생성(마이그레이션 불필요).

---

## STEP 3 — 서버에서 업로드 디렉토리 만들기

```bash
mkdir -p /services/oclyx-dev/uploads/products
```

---

## STEP 4 — GitHub Secrets 등록

각 레포 → **Settings → Secrets and variables → Actions → New repository secret**.

### backend 레포 (`spring-pms-backend`) — 신규 4개
비밀값 생성(서버/로컬 아무 데서나):
```bash
openssl rand -base64 48   # → DEV_JWT_SECRET 값
openssl rand -base64 32   # → DEV_OKLYX_CRYPTO_MASTER_KEY 값(32-byte base64)
```

| Secret 이름 | 값 |
|---|---|
| `DEV_DB_USERNAME` | STEP 2의 `<앱DB유저>` |
| `DEV_DB_PASSWORD` | STEP 2의 `<앱DB비번>` |
| `DEV_JWT_SECRET` | `openssl rand -base64 48` 결과 |
| `DEV_OKLYX_CRYPTO_MASTER_KEY` | `openssl rand -base64 32` 결과 |

> `SPRING_PROFILES_ACTIVE=dev`, `DB_URL` 은 비밀이 아니라 워크플로에 하드코딩되어 있음 → 등록 불필요.
> `SERVER_HOST` / `SERVER_USER` / `SSH_PRIVATE_KEY` / `TAILSCALE_AUTH_KEY` / `GITHUB_TOKEN` 은 PROD용으로 이미 존재 → 재사용.

### frontend 레포 (`nextjs-oklyx-front`) — 신규 1개

| Secret 이름 | 값 |
|---|---|
| `API_URL_DEV` | `https://api-dev.oclyx.com` (빌드 시 이미지에 baked) |

> `SERVER_HOST` / `SERVER_USER` / `SSH_PRIVATE_KEY` / `TAILSCALE_AUTH_KEY` / `CR_PAT` 은 이미 존재 → 재사용.

---

## STEP 5 — nginx 라우팅 + 인증서 (api-dev.* / dev.*)

> ✅ **2026-09-01 적용 완료.** 아래는 현재 서버에 반영된 최종 절차 = 새 환경 구축 시 그대로 쓰는 기준.
> (이전 버전의 `--standalone` 인증서 절차는 PROD 다운을 유발해 폐기 — 5-1 참고.)

**확정: 수동 nginx (순정 `nginx:alpine`, 컨테이너명 `nginx-proxy`).**
- 설정 파일(호스트): `/home/ydmins/services/oklyx/nginx.conf` (→ 컨테이너 `/etc/nginx/conf.d/default.conf`)
- 인증서: `/etc/letsencrypt/live/oclyx.com/` — SAN 5개(`oclyx.com`, `www`, `api`, `dev`, `api-dev`) 커버.
- ⚠️ 이 파일 하나에 PROD+DEV vhost 가 함께 들어감 → 잘못하면 PROD 도 죽음. 아래 안전 절차 필수.
- ⚠️ **호스트 파일과 컨테이너 내부 파일이 따로 놀 수 있다**(bind mount 링크 끊김 이력 있음). `docker cp` 는 호스트 경로에 쓰므로 구동 중 nginx 에 반영되지 않음 → 5-3 절차대로 **둘 다** 갱신할 것.

### 5-1. 인증서 확장 (dev·api-dev 추가) — 무중단 `--webroot`
`--standalone` 은 80 포트를 certbot 이 직접 잡아야 해서 nginx 를 내려야 한다(≈1분 PROD 다운). **쓰지 말 것.**
대신 nginx 를 띄운 채 챌린지 파일만 서빙하는 `--webroot` 를 쓴다. `/etc/letsencrypt` 는 이미 nginx 에 read-only 로 마운트돼 있으므로 **볼륨 추가·컨테이너 재생성이 필요 없다**.

```bash
# 1) 챌린지 디렉토리 (nginx 는 읽기만 하면 됨)
sudo mkdir -p /etc/letsencrypt/webroot/.well-known/acme-challenge

# 2) 80 vhost 에 챌린지 location 추가 후 reload (5-2 / 5-3 참고)

# 3) 발급 전 챌린지 경로 사전 검증 — 301 이 아니라 200 이어야 함
echo ok | sudo tee /etc/letsencrypt/webroot/.well-known/acme-challenge/test-x > /dev/null
for h in oclyx.com www.oclyx.com api.oclyx.com dev.oclyx.com api-dev.oclyx.com; do
  echo -n "$h "; curl -s http://$h/.well-known/acme-challenge/test-x; echo
done
sudo rm -f /etc/letsencrypt/webroot/.well-known/acme-challenge/test-x

# 4) 발급 (무중단, 한 줄로 실행 — 줄바꿈 `\` 뒤 공백이 있으면 인자가 깨진다)
sudo certbot certonly --webroot -w /etc/letsencrypt/webroot --expand --cert-name oclyx.com -n --agree-tos -d oclyx.com -d www.oclyx.com -d api.oclyx.com -d dev.oclyx.com -d api-dev.oclyx.com --deploy-hook "/usr/bin/docker exec nginx-proxy nginx -s reload"

# 5) 갱신 리허설 (실제 인증서 미변경)
sudo certbot renew --dry-run
```
- ⚠️ **`--deploy-hook` 필수.** webroot 는 nginx 를 재시작하지 않으므로, 이게 없으면 갱신에 성공해도 nginx 가 옛 인증서를 계속 서빙한다. 갱신 설정(`/etc/letsencrypt/renewal/oclyx.com.conf`)에 `renew_hook` 으로 저장된다.
- ⚠️ **갱신 authenticator 확인 필수.** `standalone` 인 채로 두면 nginx 가 80 을 점유하고 있어 `certbot.timer`(하루 2회) 갱신이 **조용히 실패 → 인증서 만료**한다. 위 명령이 `authenticator = webroot` 로 바꿔준다. 확인:
  `docker exec nginx-proxy grep -E "authenticator|renew_hook" /etc/letsencrypt/renewal/oclyx.com.conf`

### 5-2. `/home/ydmins/services/oklyx/nginx.conf` — vhost 패턴
**⚠️ `proxy_pass` 는 반드시 아래 변수 패턴을 쓸 것.** 컨테이너명을 리터럴로 쓰면 nginx 가 **설정 로드 시점에 한 번만** DNS 해석하고 IP 를 영구 캐싱한다 → 배포로 컨테이너가 재생성될 때마다 502(옛 IP). 변수를 쓰면 요청 시점에 재해석되어 reload 없이 자동 반영된다.

```nginx
# 파일 최상단 (http 컨텍스트) — Docker 내장 DNS
resolver 127.0.0.11 valid=10s ipv6=off;

# --- DEV: 80 (ACME 챌린지 + HTTPS 리다이렉트) ---
server {
    listen 80;
    server_name api-dev.oclyx.com dev.oclyx.com;
    # ⚠️ 서버 레벨 `return 301` 로 쓰면 rewrite 단계가 location 보다 먼저 돌아
    #    챌린지 요청까지 리다이렉트된다 → 반드시 location / 안에 넣을 것.
    location /.well-known/acme-challenge/ {
        root /etc/letsencrypt/webroot;
    }
    location / {
        return 301 https://$host$request_uri;
    }
}
server {
    listen 443 ssl;
    server_name dev.oclyx.com;
    ssl_certificate /etc/letsencrypt/live/oclyx.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/oclyx.com/privkey.pem;
    client_max_body_size 20M;
    location / {
        set $up_front_dev nextjs-oklyx-front-dev;
        proxy_pass http://$up_front_dev:3000$request_uri;   # ⚠️ $request_uri 필수
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
server {
    listen 443 ssl;
    server_name api-dev.oclyx.com;
    ssl_certificate /etc/letsencrypt/live/oclyx.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/oclyx.com/privkey.pem;
    client_max_body_size 20M;
    location / {
        set $up_api_dev spring-pms-backend-dev;
        proxy_pass http://$up_api_dev:8083$request_uri;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```
- ⚠️ **`$request_uri` 를 빼면 안 된다.** `proxy_pass` 에 변수가 들어가면 nginx 가 원래 URI 를 자동 전달하지 않아, 모든 요청이 `/` 로 간다.
- PROD vhost 도 동일 패턴으로 되어 있다(`$up_front`, `$up_api`).

### 5-3. 안전하게 적용 (⚠️ `restart` 금지, `reload` 만)
```bash
# 0) 변경 전 실구동본 백업 (호스트 파일이 아니라 컨테이너 내부 파일이 진짜 실구동본)
docker exec nginx-proxy cat /etc/nginx/conf.d/default.conf > ~/services/oklyx/backup-nginx/LIVE-$(date +%Y%m%d-%H%M%S).conf

# 1) 실제 반영 전 동일 이미지 임시 컨테이너로 문법 검증 (구동 중 nginx 무영향)
docker run --rm --network ydmins_proxy_network \
  -v /home/ydmins/services/oklyx/nginx.conf:/etc/nginx/conf.d/default.conf:ro \
  -v /etc/letsencrypt:/etc/letsencrypt:ro nginx:alpine nginx -t

# 2) 호스트 파일 → 컨테이너 내부로 주입 (docker cp 는 호스트 경로에 써서 무효)
sudo docker exec -i nginx-proxy tee /etc/nginx/conf.d/default.conf < /home/ydmins/services/oklyx/nginx.conf > /dev/null

# 3) 검증 후 무중단 반영
sudo docker exec nginx-proxy nginx -t
sudo docker exec nginx-proxy nginx -s reload
```
- `nginx -t` 실패 시 절대 `docker restart nginx-proxy` 하지 말 것(안 뜨면 PROD 다운). 설정 고쳐서 `-t` 통과부터.
- nginx-proxy 는 PROD 컨테이너로 이미 `ydmins_proxy_network` 에 있으므로 `*-dev` 컨테이너도 같은 네트워크(워크플로가 연결)면 이름으로 도달.
- 백업 위치: `/home/ydmins/services/oklyx/backup-nginx/`

---

## STEP 6 — frontend `develop` 브랜치 만들기

frontend 레포는 현재 `main` 만 있음. `develop` 이 없으면 fe 배포가 트리거되지 않는다.
```bash
# frontend 레포에서 (사용자가 실행)
git checkout -b develop && git push -u origin develop
```
(backend 레포는 `develop` 이미 존재.)

---

## STEP 7 — 배포 & 검증

1. STEP 1~6 완료.
2. 각 레포 `develop` 에 push → `deploy-dev.yml` 자동 트리거.
3. GitHub Actions 로그: 빌드 → ghcr push → SSH 배포까지 초록 확인.
4. 서버: `docker ps | grep -E 'spring-pms-backend-dev|nextjs-oklyx-front-dev'` → 둘 다 running.
5. 응답 확인:
   - `curl -f https://api-dev.oclyx.com/api/health` → 200
   - 브라우저 `https://dev.oclyx.com` → 프론트 로드, 로그인 시 api-dev 로 요청.
6. `docker logs spring-pms-backend-dev` → 부팅 배너 `ENV: DEV` + fail-fast 통과.

---

## 체크리스트

- [ ] STEP 1  DNS: dev / api-dev → 서버 IP
- [ ] STEP 2  서버: `db-pms-dev` 컨테이너 + 볼륨
- [ ] STEP 3  서버: `/services/oclyx-dev/uploads/products`
- [ ] STEP 4  GitHub Secrets: backend 4개(DEV_*) + frontend 1개(API_URL_DEV)
- [x] STEP 5  nginx: api-dev/dev vhost + 인증서 (2026-09-01 완료 — resolver 변수 proxy_pass + webroot 무중단 갱신)
- [ ] STEP 6  frontend `develop` 브랜치 생성
- [ ] STEP 7  develop push → 배포 초록 → api-dev/dev 응답 확인
