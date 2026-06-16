package com.pms.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts a String column at rest (AES-256-GCM).
 *
 * 용도: 외부 플랫폼 자격증명(secret_key) 같은 민감 값을 DB에 평문으로 보관하지 않기 위함.
 *       엔티티 필드에 {@code @Convert(converter = AesAttributeConverter.class)} 를 붙이면
 *       저장 시 자동 암호화, 조회 시 자동 복호화된다.
 *
 * 저장 포맷: Base64( iv(12B) ∥ ciphertext+tag ). GCM 이라 매 암호화마다 랜덤 IV 사용 → 같은 평문도 매번 다른 암호문.
 *
 * 마스터키: 32바이트(AES-256) Base64 문자열. 환경변수 OKLYX_CRYPTO_MASTER_KEY 로 주입
 *           (설정 키: oklyx.crypto.master-key).
 *
 * ⚠️ 주의:
 * - 마스터키가 바뀌면 기존 암호문은 복호화 불가. 키 로테이션은 별도 재암호화 절차 필요.
 * - 평문을 응답 DTO 로 직렬화하지 말 것 (secretKey 는 응답에서 제외).
 *
 * @see com.pms.domain.MarketplaceAccount
 */
@Converter
@Component
public class AesAttributeConverter implements AttributeConverter<String, String> {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesAttributeConverter(@Value("${oklyx.crypto.master-key}") String masterKeyBase64) {
        // 마스터키: 32바이트(AES-256) Base64. 환경변수 OKLYX_CRYPTO_MASTER_KEY 로 주입.
        this.key = new SecretKeySpec(Base64.getDecoder().decode(masterKeyBase64), "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return Base64.getEncoder().encodeToString(out);   // iv∥ciphertext
        } catch (Exception e) {
            throw new IllegalStateException("Encrypt failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decrypt failed", e);
        }
    }
}
