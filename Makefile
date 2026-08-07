# OKLYX local dev — 한 개의 명령으로 백엔드+프론트 기동.
#
# 전제:
#   - spring-pms-backend/ 와 nextjs-oklyx-front/ 가 형제 디렉토리(oklyx/ 아래 나란히).
#   - 이 Makefile 은 backend 레포 루트(= spring-pms-backend/) 에 둔다.
#   - Gradle 은 Java 17 필요. 기본 java 가 11(jenv) 일 수 있어 아래에서 JDK17 을 자동 지정.
#
# ⚠️ 로컬 편의 도구 전용. prod 기동/배포 명령은 여기 넣지 않는다(실수 방지).
#    배포는 GitHub Actions(.github/workflows/deploy.yml) 가 담당.

FE_DIR ?= ../nextjs-oklyx-front
# 로컬 포트(고정 아님). 충돌 시: make up BE_PORT=8085 FE_PORT=3001
BE_PORT ?= 8083
FE_PORT ?= 3000
# make test-be 기본 스모크 클래스. 전체 스위트는 H2 공유DB 이슈로 flaky → per-class 로만 게이트.
# 다른 클래스: make test-be TEST_CLASS=CarrierRateServiceTest
TEST_CLASS ?= com.pms.service.CarrierRateServiceTest

# macOS: JDK17 자동 지정(있으면). 없으면(비-mac 등) 시스템 java 사용.
# 모든 recipe 셸에 export → gradlew 가 Java 17 로 실행됨.
JAVA17_HOME := $(shell /usr/libexec/java_home -v 17 2>/dev/null)
ifneq ($(JAVA17_HOME),)
export JAVA_HOME := $(JAVA17_HOME)
endif

.PHONY: up be-local fe-local be-mysql test-be down help
.DEFAULT_GOAL := help

help:
	@echo "OKLYX local dev targets:"
	@echo "  make up        - 백엔드(local, H2) + 프론트(next dev) 동시 기동 (Ctrl-C 또는 make down 으로 정리)"
	@echo "                   포트 충돌 시: make up BE_PORT=8085 FE_PORT=3001"
	@echo "  make be-local  - 백엔드만 (SPRING_PROFILES_ACTIVE=local, H2)"
	@echo "  make fe-local  - 프론트만 (npm run dev)"
	@echo "  make be-mysql  - MySQL 동등성 로컬 검증 (기존 docker-compose.yml, 선택)"
	@echo "  make test-be   - 백엔드 per-class 테스트 (기본 $(TEST_CLASS); TEST_CLASS= 로 변경)"
	@echo "  make down      - up/compose 로 띄운 프로세스 정리"

# 동시 기동: 단일 셸(백그라운드 + wait)로 실행해야 wait 가 자식들을 잡는다.
# Ctrl-C 시 trap 'kill 0' 로 프로세스 그룹 전체 종료. pidfile 은 서브셸 PID라 best-effort
# (실제 정리는 make down 의 pkill fallback 이 JVM/next 자식까지 담당).
up:
	@mkdir -p .run
	@trap 'kill 0' INT TERM; \
	( SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--server.port=$(BE_PORT)' ) & echo $$! > .run/be.pid; \
	( cd $(FE_DIR) && npm run dev -- -p $(FE_PORT) ) & echo $$! > .run/fe.pid; \
	echo "up: be(:$(BE_PORT) pid $$(cat .run/be.pid)) fe(:$(FE_PORT) pid $$(cat .run/fe.pid)) — Ctrl-C 또는 'make down' 으로 정리"; \
	wait

be-local:
	SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--server.port=$(BE_PORT)'

fe-local:
	cd $(FE_DIR) && npm run dev -- -p $(FE_PORT)

# 기존 docker-compose.yml = local build(app, profile=dev) + MySQL(127.0.0.1:3307).
# 별도 docker-compose.dev.yml 을 만들지 않고 기존 파일을 재사용(중복 금지).
# MySQL 컨테이너는 .env(env_file) 필요 — 없으면 먼저 생성.
be-mysql:
	docker compose up

test-be:
	./gradlew test --tests "$(TEST_CLASS)"

down:
	-@[ -f .run/be.pid ] && kill $$(cat .run/be.pid) 2>/dev/null; rm -f .run/be.pid
	-@[ -f .run/fe.pid ] && kill $$(cat .run/fe.pid) 2>/dev/null; rm -f .run/fe.pid
	-@pkill -f "gradlew bootRun" 2>/dev/null; true
	-@pkill -f "bootRun" 2>/dev/null; true
	-@pkill -f "next dev" 2>/dev/null; true
	-@pkill -f "next-server" 2>/dev/null; true
	@echo "down: 로컬 프로세스 정리 완료 (compose 는 'docker compose down' 으로 별도 정리)"
