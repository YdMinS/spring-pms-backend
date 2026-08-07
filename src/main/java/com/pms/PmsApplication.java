package com.pms;

import com.pms.config.StartupEnvironmentValidatorListener;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PmsApplication {

    public static void main(String[] args) {
        // ENV 배너 + dev/prod 필수 secret fail-fast 를 빈 생성 이전(ApplicationPreparedEvent)에 실행한다.
        new SpringApplicationBuilder(PmsApplication.class)
                .listeners(new StartupEnvironmentValidatorListener())
                .run(args);
    }
}
