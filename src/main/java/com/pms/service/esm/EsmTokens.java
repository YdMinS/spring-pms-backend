package com.pms.service.esm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Builds the ESM bearer token, a new one for every call (FEATURE_2610_26 / D6): header {@code alg} HS256 ·
 * {@code typ} JWT · {@code kid} = master ID, payload {@code iss} {@value #ISSUER} · {@code sub} {@value #SUBJECT} ·
 * {@code aud} {@value #AUDIENCE} · {@code iat} = now in seconds · {@code ssi} = {@code A:{Auction ID},G:{Gmarket ID}}
 * (Auction first; one site only for {@link EsmSite#GMARKET} / {@link EsmSite#AUCTION}).
 *
 * <p>D69: signed here with the JDK {@code javax.crypto.Mac} (HmacSHA256) — header and payload JSON each as unpadded
 * base64url joined by 「.」, signed with the secret key's UTF-8 bytes as given, the signature appended as unpadded
 * base64url. No key length check. ⚠️ Do not switch this to {@code jjwt}: it refuses keys under 256 bits, and the ESM
 * guide's sample key is 192 bits. The login token ({@code JwtTokenProvider}) keeps {@code jjwt}.</p>
 */
public final class EsmTokens {

    static final String ISSUER = "www.esmplus.com";
    static final String SUBJECT = "sell";
    static final String AUDIENCE = "sa.esmplus.com";

    private static final String ALGORITHM = "HmacSHA256";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private EsmTokens() {
    }

    /** The token (without the {@code Bearer } prefix). {@code aud} goes out as one string like the ESM guide sample. */
    public static String token(EsmKey key, EsmSite site, long issuedAtSeconds) {
        ObjectNode header = JSON.createObjectNode()
                .put("alg", "HS256")
                .put("typ", "JWT")
                .put("kid", key.masterId());
        ObjectNode payload = JSON.createObjectNode()
                .put("iss", ISSUER)
                .put("sub", SUBJECT)
                .put("aud", AUDIENCE)
                .put("iat", issuedAtSeconds)
                .put("ssi", ssi(key, site));
        String signingInput = encode(header) + "." + encode(payload);
        return signingInput + "." + BASE64URL.encodeToString(sign(key.secretKey(), signingInput));
    }

    /** D6: {@code A:{Auction ID},G:{Gmarket ID}}, or the one site the lookup asks for. */
    static String ssi(EsmKey key, EsmSite site) {
        return switch (site) {
            case GMARKET -> "G:" + key.gmarketSellerId();
            case AUCTION -> "A:" + key.auctionSellerId();
            case BOTH -> "A:" + key.auctionSellerId() + ",G:" + key.gmarketSellerId();
        };
    }

    private static String encode(ObjectNode json) {
        try {
            return BASE64URL.encodeToString(JSON.writeValueAsBytes(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write the ESM token JSON", e);
        }
    }

    private static byte[] sign(String secretKey, String signingInput) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign the ESM token", e);
        }
    }
}
