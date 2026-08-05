package com.pms.config;

import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * {@link StartupEnvironmentValidator} 를 빈 생성 이전({@code ApplicationPreparedEvent})에 실행시키는 리스너.
 *
 * <p>빈 생성 이후({@code ApplicationReadyEvent})에 검증하면 {@code JwtTokenProvider} 등이 약한 secret 으로
 * 먼저 실패해 친절한 메시지가 가려진다. 이 리스너는 컨텍스트 refresh 이전에 호출되어, env 누락 시
 * "어떤 env var 가 필요한지" 명확한 메시지로 먼저 부팅을 중단시킨다.
 *
 * <p>{@link com.pms.PmsApplication#main} 에서 {@code SpringApplicationBuilder.listeners(...)} 로 등록한다.
 * (일반 {@code @Component}/@EventListener 는 빈 생성 이후에야 동작하므로 여기선 쓸 수 없다.)
 */
public class StartupEnvironmentValidatorListener implements ApplicationListener<ApplicationPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        StartupEnvironmentValidator
                .fromEnvironment(event.getApplicationContext().getEnvironment())
                .validate();
    }
}
