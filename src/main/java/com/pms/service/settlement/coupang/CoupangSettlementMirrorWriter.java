package com.pms.service.settlement.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.CoupangSettlementLine;
import com.pms.domain.Platform;
import com.pms.domain.SettlementLine;
import com.pms.repository.CoupangSettlementLineRepository;
import com.pms.service.settlement.SettlementMirrorWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 쿠팡 정산 라인 미러 기록 (FEATURE_2609_30 / PLAN D6).
 *
 * <p>쿠팡 필드명을 아는 곳은 여기와 {@link CoupangSettlementSource} 뿐이다 — 중립 upserter 가
 * {@code couranteeFee} 같은 이름을 알게 되면 네이버가 붙는 순간 중립 코드가 갈라진다.
 *
 * <p>⚠️ 트랜잭션을 스스로 열지 않는다 — 호출자({@code SettlementLineUpserter}, REQUIRES_NEW)의 경계에
 * 합류해 중립 라인과 미러가 <b>같이 커밋되거나 같이 롤백</b>된다.
 *
 * <p>⚠️ 미러는 갱신형이다(정정 재조회가 원문을 덮는다). 이력이 필요하면 별도 테이블을 만든다.
 */
@Component
@RequiredArgsConstructor
public class CoupangSettlementMirrorWriter implements SettlementMirrorWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoupangSettlementLineRepository coupangSettlementLineRepository;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public void write(SettlementLine line, JsonNode raw) {
        if (raw == null) {
            return;
        }
        CoupangSettlementLine existing = coupangSettlementLineRepository
                .findBySettlementLine_Id(line.getId())
                .orElse(null);

        CoupangSettlementLine.CoupangSettlementLineBuilder builder =
                (existing == null ? CoupangSettlementLine.builder() : existing.toBuilder());

        coupangSettlementLineRepository.save(builder
                .settlementLine(line)
                .marketplaceAccount(line.getMarketplaceAccount())
                .orderIdRaw(line.getExternalOrderId())
                .platformOptionId(line.getPlatformOptionId())
                .platformProductId(text(raw, "productId", "sellerProductId"))
                .productName(text(raw, "productName", "vendorItemName"))
                .taxType(text(raw, "taxType"))
                .couranteeFee(decimal(raw, "couranteeFee"))
                .couranteeFeeVat(decimal(raw, "couranteeFeeVat"))
                .storeFeeDiscount(decimal(raw, "storeFeeDiscount"))
                .downloadableCoupon(decimal(raw, "downloadableCoupon"))
                .sellerDiscountCoupon(decimal(raw, "sellerDiscountCoupon"))
                .coupangDiscountCoupon(decimal(raw, "coupangDiscountCoupon"))
                .externalSellerSkuCode(text(raw, "externalSellerSkuCode", "externalVendorSkuCode"))
                .settlementDate(date(raw, "settlementDate"))
                .finalSettlementDate(date(raw, "finalSettlementDate"))
                .raw(raw.toString())
                .build());
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String raw = value.asText("");
                if (!raw.isBlank()) {
                    return raw.trim();
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        String raw = text(node, keys);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(JsonNode node, String... keys) {
        String raw = text(node, keys);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw, DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
