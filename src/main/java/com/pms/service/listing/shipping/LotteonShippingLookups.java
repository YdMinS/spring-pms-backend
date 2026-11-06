package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCredentials;
import com.pms.service.lotteon.LotteonKey;
import com.pms.service.lotteon.LotteonPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two LotteON shipping lists of one sales channel (FEATURE_2610_24 / D43), read with its key and its seller number
 * ({@code afflTrCd} = D5 {@code trNo}): places (150) and shipping fee policies (166). In-use rows only.
 */
@Component
@RequiredArgsConstructor
public class LotteonShippingLookups {

    /** 150 {@code dvpTypCd}: 01 return place · 02 outbound place. */
    public static final String RETURN_PLACE = "01";
    public static final String OUTBOUND_PLACE = "02";
    /** 166 {@code dvCstTypCd} of a shipping fee policy (the extra-fee policies are not offered, D43). */
    static final String FEE_POLICY_TYPE = "DV_CST";

    private final LotteonApiClient client;

    /**
     * One shipping fee policy (D43 · D65).
     *
     * @param freeOver {@code cndlFreeStdAmt}; null when LotteON gives none
     */
    public record FeePolicy(String policyNo, String name, String dvsCd, BigDecimal fee, BigDecimal freeOver) {
    }

    /** 150: the {@code useYn = Y} places of type {@code dvpTypCd}, LotteON's order. */
    public List<JsonNode> places(MarketplaceAccount account, String dvpTypCd) {
        List<JsonNode> places = new ArrayList<>();
        for (JsonNode place : call(account, LotteonPaths.PLACES).path("data")) {
            if ("Y".equals(place.path("useYn").asText()) && dvpTypCd.equals(place.path("dvpTypCd").asText())) {
                places.add(place);
            }
        }
        return places;
    }

    /** 166: the {@code useYn = Y} · {@code dvCstTypCd = DV_CST} policies, LotteON's order. */
    public List<FeePolicy> feePolicies(MarketplaceAccount account) {
        List<FeePolicy> policies = new ArrayList<>();
        for (JsonNode policy : call(account, LotteonPaths.FEE_POLICIES).path("data")) {
            if (!"Y".equals(policy.path("useYn").asText())
                    || !FEE_POLICY_TYPE.equals(policy.path("dvCstTypCd").asText())) {
                continue;
            }
            policies.add(new FeePolicy(policy.path("dvCstPolNo").asText(), policy.path("dvCstPolNm").asText(""),
                    policy.path("dvCstDvsCd").asText(""), amount(policy.get("dvCst")),
                    amount(policy.get("cndlFreeStdAmt"))));
        }
        return policies;
    }

    private JsonNode call(MarketplaceAccount account, String path) {
        LotteonKey key = LotteonCredentials.of(account);
        return client.post(path, Map.of("afflTrCd", key.trNo() == null ? "" : key.trNo()), key.apiKey());
    }

    private static BigDecimal amount(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
