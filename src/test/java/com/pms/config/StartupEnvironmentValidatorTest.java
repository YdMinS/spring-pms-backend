package com.pms.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * StartupEnvironmentValidator fail-fast 검증.
 * prod 프로파일에서 crypto master-key 가 application.yml placeholder 면 부팅을 중단해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class StartupEnvironmentValidatorTest {

    @Mock
    private Environment environment;

    private static final String VALID_JWT = "a-sufficiently-long-jwt-secret-over-32-bytes!!";

    @Test
    void prod_cryptoMasterKey가placeholder면_부팅중단() {
        given(environment.getActiveProfiles()).willReturn(new String[]{"prod"});
        StartupEnvironmentValidator validator = new StartupEnvironmentValidator(
                environment,
                VALID_JWT,
                StartupEnvironmentValidator.CRYPTO_PLACEHOLDER, // prod 에서 금지된 placeholder
                "jdbc:mysql://localhost:3306/oklyx",
                "root");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OKLYX_CRYPTO_MASTER_KEY");
    }

    @Test
    void local_더미secret이어도_통과() {
        given(environment.getActiveProfiles()).willReturn(new String[]{"local"});
        StartupEnvironmentValidator validator = new StartupEnvironmentValidator(
                environment,
                StartupEnvironmentValidator.UNSET,
                StartupEnvironmentValidator.CRYPTO_PLACEHOLDER,
                "jdbc:h2:mem:oklyx_local",
                "sa");

        // local 은 검사 skip — 예외 없이 반환.
        validator.validate();
    }
}
