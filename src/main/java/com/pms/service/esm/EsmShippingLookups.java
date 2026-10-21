package com.pms.service.esm;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ESM shipping lists a seller picks from (FEATURE_2610_26 / D37 · D68) — read from ESM+, never created by us
 * (D35). Shipping places (post 17) and the address book (post 16) feed {@code EsmShippingPlaceProvider}; bundle-fee
 * policies (post 18), dispatch policies (post 19) and couriers (post 142) are read here directly.
 *
 * <p>Errors propagate (503 on a server that does not call ESM, 502 with ESM's text otherwise).</p>
 */
@Component
@RequiredArgsConstructor
public class EsmShippingLookups {

    /** D37: shipping places and the address book are read in pages of this size, from page 1 to the end. */
    static final int PAGE_SIZE = 500;

    /** D68: post 19 dispatch types → their names, used when the policy itself has no name. */
    static final Map<String, String> DISPATCH_TYPE_NAMES = Map.of(
            "A", "당일배송",
            "B", "순차발송",
            "C", "해외발송",
            "D", "요청일발송",
            "E", "주문제작발송",
            "F", "발송일미정");

    private final EsmApiClient client;

    /** A picked value: {@code code} is what is stored and sent, {@code name} what the seller sees. */
    public record Choice(String code, String name) {
    }

    /**
     * One bundle-fee policy of a shipping place (post 18). {@code freeOver} is set for a conditional policy (3) only.
     */
    public record BundlePolicy(String policyNo, String feeType, BigDecimal fee, BigDecimal freeOver,
                               boolean prepay, String name) {
    }

    /** D37: every shipping place ({@code placeNo} · {@code placeName}). */
    public List<JsonNode> shippingPlaces(MarketplaceAccount account) {
        return pages(EsmPaths.SHIPPING_PLACES, "shippingPlaces", account);
    }

    /** D37: every address-book entry ({@code addrNo} · {@code addrName} · {@code addr1} …). */
    public List<JsonNode> addresses(MarketplaceAccount account) {
        return pages(EsmPaths.ADDRESSES, "sellerAddressBookList", account);
    }

    /** D37 · D60: the bundle-fee policies of one shipping place, each with its screen name. */
    public List<BundlePolicy> bundlePolicies(MarketplaceAccount account, String placeNo) {
        JsonNode body = client.get(String.format(EsmPaths.PLACE_POLICIES, placeNo), EsmCredentials.of(account));
        List<BundlePolicy> policies = new ArrayList<>();
        for (JsonNode policy : body.path("policies")) {
            String feeType = policy.path("feeType").asText();
            BigDecimal fee = amount(policy.get("fee"));
            BigDecimal freeOver = "3".equals(feeType) ? amount(policy.path("shippingFee").path(0).get("condition"))
                    : null;
            policies.add(new BundlePolicy(policy.path("policyNo").asText(), feeType, fee, freeOver,
                    policy.path("isPrepayment").asBoolean(), bundleName(feeType, fee, freeOver,
                    policy.path("policyNo").asText())));
        }
        return policies;
    }

    /** D68: 「{policy name} ({number})」, the dispatch type's name when ESM sent no policy name. */
    public List<Choice> dispatchPolicies(MarketplaceAccount account) {
        JsonNode body = client.get(EsmPaths.DISPATCH_POLICIES, EsmCredentials.of(account));
        List<Choice> choices = new ArrayList<>();
        for (JsonNode policy : body.path("dispatchPolicies")) {
            String number = policy.path("dispatchPolicyNo").asText();
            String name = policy.path("dispatchPolicyName").asText("");
            if (name.isBlank()) {
                name = DISPATCH_TYPE_NAMES.getOrDefault(policy.path("dispatchType").asText(), "");
            }
            choices.add(new Choice(number, name + " (" + number + ")"));
        }
        return choices;
    }

    /** D37: the couriers ({@code deliveryCompCode} · {@code deliveryCompName}). */
    public List<Choice> deliveryCompanies(MarketplaceAccount account) {
        JsonNode body = client.get(EsmPaths.DELIVERY_COMPANIES, EsmCredentials.of(account));
        List<Choice> choices = new ArrayList<>();
        for (JsonNode company : body.path("deliveryCompanies")) {
            choices.add(new Choice(company.path("deliveryCompCode").asText(),
                    company.path("deliveryCompName").asText()));
        }
        return choices;
    }

    /** D37: 「무료」 · 「유료 {fee}원」 · 「조건부 {fee}원 ({freeOver}원 이상 무료)」; another type → the policy number. */
    static String bundleName(String feeType, BigDecimal fee, BigDecimal freeOver, String policyNo) {
        return switch (feeType) {
            case "1" -> "무료";
            case "2" -> "유료 " + plain(fee) + "원";
            case "3" -> "조건부 " + plain(fee) + "원 (" + plain(freeOver) + "원 이상 무료)";
            default -> policyNo;
        };
    }

    /** Pages of {@link #PAGE_SIZE} from page 1 until a page comes back short. */
    private List<JsonNode> pages(String path, String listField, MarketplaceAccount account) {
        EsmKey key = EsmCredentials.of(account);
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = client.get(path + "?pageSize=" + PAGE_SIZE + "&pageIndex=" + page, key);
            int before = rows.size();
            body.path(listField).forEach(rows::add);
            if (rows.size() - before < PAGE_SIZE) {
                return rows;
            }
        }
    }

    private static BigDecimal amount(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? BigDecimal.ZERO
                : new BigDecimal(node.asText());
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
