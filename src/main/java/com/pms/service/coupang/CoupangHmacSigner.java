package com.pms.service.coupang;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 쿠팡 OpenAPI CEA HMAC-SHA256 Authorization 헤더 생성기 (SDK 미사용, 직접 구현).
 *
 * 서명 대상 message = signed-date + method + path + query.
 * signed-date 는 요청마다 현재 UTC 시각(yyMMdd'T'HHmmss'Z')이라 매번 다른 서명이 나온다.
 *
 * Phase 2~3 의 ordersheets/returnRequests 호출이 {@link CoupangApiClient} 를 통해 이 서명을 사용한다.
 */
@Component
public class CoupangHmacSigner {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Authorization 헤더 문자열 생성.
     *
     * @param method    HTTP 메서드 ("GET" 등)
     * @param path      쿼리 제외 경로
     * @param query     쿼리스트링 (없으면 "")
     * @param accessKey 계정 access key
     * @param secretKey 계정 secret key (복호화된 평문)
     */
    public String authorization(String method, String path, String query,
                                String accessKey, String secretKey) {
        String datetime = FMT.format(Instant.now());
        String message = datetime + method + path + query;     // 서명 대상
        String signature = hmacSha256Hex(secretKey, message);
        return "CEA algorithm=HmacSHA256, access-key=" + accessKey
                + ", signed-date=" + datetime + ", signature=" + signature;
    }

    private String hmacSha256Hex(String secret, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
