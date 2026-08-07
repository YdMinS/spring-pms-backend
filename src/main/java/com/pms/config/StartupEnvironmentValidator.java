package com.pms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 부팅 가드레일 — 활성 프로파일 배너 출력 + (dev/prod) 필수 secret fail-fast.
 *
 * <p><b>배너</b>: 활성 프로파일(LOCAL/DEV/PROD)·DB 종류·마켓 모드를 로그로 크게 출력한다.
 *
 * <p><b>fail-fast</b>: 프로파일이 {@code local} 이 아닐 때, 아래가 비었거나 placeholder 면
 * {@link IllegalStateException} 을 던져 부팅을 중단한다(어떤 env var 가 필요한지 메시지에 명시).
 * <ul>
 *   <li>{@code jwt.secret} — 미설정(sentinel {@value #UNSET}) 또는 32byte 미만</li>
 *   <li>{@code oklyx.crypto.master-key} — 미설정 또는 application.yml 의 placeholder 기본값</li>
 *   <li>{@code spring.datasource.url} / {@code spring.datasource.username} — 미설정(sentinel {@value #UNSET})</li>
 * </ul>
 * {@code local} 은 더미 secret 을 허용하므로 검사를 건너뛴다.
 *
 * <p><b>실행 시점(중요)</b>: {@link StartupEnvironmentValidatorListener} 가 {@code ApplicationPreparedEvent}
 * (빈 생성 이전)에 이 검증을 호출한다. {@code ApplicationReadyEvent}(빈 생성 이후)에 돌리면
 * {@code JwtTokenProvider} 가 약한 {@code jwt.secret} 로 먼저 죽어(WeakKeyException) 친절한 메시지가
 * 안 뜨기 때문이다 — 그래서 일부러 더 이르게 실행한다.
 *
 * <p><b>테스트 가능성(주의 C)</b>: 값들을 생성자로 주입받아, 단위 테스트에서 prod/placeholder 상태를
 * 직접 구성해 {@link #validate()} 를 호출할 수 있다.
 */
public class StartupEnvironmentValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupEnvironmentValidator.class);

    /** application.yml 의 crypto master-key placeholder 기본값 — 이 값이면 prod 미설정으로 간주. */
    static final String CRYPTO_PLACEHOLDER = "P5m4nQbR+G+j8ekvlzQpnRgmSWgGSQWdJCzZCndLNew=";
    /** yml sentinel default — env 미주입을 뜻함. */
    static final String UNSET = "__UNSET__";
    private static final int MIN_JWT_BYTES = 32;
    private static final String LOCAL_PROFILE = "local";

    private final Environment environment;
    private final String jwtSecret;
    private final String cryptoMasterKey;
    private final String datasourceUrl;
    private final String datasourceUsername;

    public StartupEnvironmentValidator(
            Environment environment,
            String jwtSecret,
            String cryptoMasterKey,
            String datasourceUrl,
            String datasourceUsername) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.cryptoMasterKey = cryptoMasterKey;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
    }

    /** Environment 에서 프로퍼티(sentinel default 포함)를 읽어 validator 를 조립한다. */
    static StartupEnvironmentValidator fromEnvironment(Environment env) {
        return new StartupEnvironmentValidator(
                env,
                env.getProperty("jwt.secret", UNSET),
                env.getProperty("oklyx.crypto.master-key", UNSET),
                env.getProperty("spring.datasource.url", UNSET),
                env.getProperty("spring.datasource.username", UNSET));
    }

    /** 배너 출력 후, local 이 아니면 필수 secret 을 검사한다. 문제가 있으면 IllegalStateException. */
    void validate() {
        boolean local = isLocal();
        printBanner(local);

        if (local) {
            return; // 로컬은 더미 secret 허용 — skip
        }

        List<String> missing = new ArrayList<>();
        if (isBlankOrUnset(jwtSecret) || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_BYTES) {
            missing.add("JWT_SECRET");
        }
        if (isBlankOrUnset(cryptoMasterKey) || CRYPTO_PLACEHOLDER.equals(cryptoMasterKey)) {
            missing.add("OKLYX_CRYPTO_MASTER_KEY");
        }
        if (isBlankOrUnset(datasourceUrl)) {
            missing.add("DB_URL");
        }
        if (isBlankOrUnset(datasourceUsername)) {
            missing.add("DB_USERNAME");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "부팅 중단: 필수 환경변수가 없거나 placeholder 입니다 → " + String.join(", ", missing)
                            + " 필요 (프로파일=" + resolveEnvLabel() + ")");
        }
    }

    private void printBanner(boolean local) {
        String env = resolveEnvLabel();
        String dbKind = resolveDbKind();
        String marketplaceMode = local ? "MOCK" : "LIVE";
        log.info("========================================");
        log.info("  ENV: {}   (DB={}, marketplace={})", env, dbKind, marketplaceMode);
        log.info("========================================");
    }

    private boolean isLocal() {
        return activeProfiles().contains(LOCAL_PROFILE);
    }

    /** 활성 프로파일 → LOCAL/DEV/PROD 라벨(그 외는 프로파일명 대문자, 없으면 DEFAULT). */
    private String resolveEnvLabel() {
        List<String> profiles = activeProfiles();
        if (profiles.contains(LOCAL_PROFILE)) return "LOCAL";
        if (profiles.contains("prod")) return "PROD";
        if (profiles.contains("dev")) return "DEV";
        return profiles.isEmpty() ? "DEFAULT" : profiles.get(0).toUpperCase();
    }

    private String resolveDbKind() {
        String url = datasourceUrl == null ? "" : datasourceUrl.toLowerCase();
        if (url.contains("h2")) return "H2";
        if (url.contains("mysql")) return "MySQL";
        return "UNKNOWN";
    }

    private List<String> activeProfiles() {
        return Arrays.asList(environment.getActiveProfiles());
    }

    private static boolean isBlankOrUnset(String value) {
        return value == null || value.isBlank() || UNSET.equals(value);
    }
}
