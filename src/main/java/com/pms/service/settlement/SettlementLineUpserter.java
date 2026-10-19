package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SettlementLine;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementLineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SettlementLineDraft} → idempotent {@code settlement_line} upsert + order-line matching
 * (FEATURE_2609_30 / PLAN D7 · D10).
 *
 * <p>⚠️ {@code REQUIRES_NEW} — the caller ({@code SettlementSyncServiceImpl}) is an outbound HTTP loop with no
 * transaction. One page = one transaction, so a failure on page 3 keeps pages 1~2
 * (same stance as {@code InquiryUpserter} · {@code ClaimUpserter}).
 *
 * <p>🔴 <b>No delete-insert</b> (D10). Settlements are corrected after confirmation, so re-reads are frequent;
 * deleting and inserting again would wipe the {@code order_line} mapping and the payout attribution (filled by the
 * payout load — for 11st by {@code ElevenstPayoutBuilder}, FEATURE_2610_20 / D29). Corrections are applied only by
 * UPDATE of the amount fields.
 */
@Slf4j
@Service
public class SettlementLineUpserter {

    private final SettlementLineRepository settlementLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final Map<Platform, SettlementMirrorWriter> mirrorWriters = new EnumMap<>(Platform.class);

    public SettlementLineUpserter(SettlementLineRepository settlementLineRepository,
                                  ProductListingOptionRepository productListingOptionRepository,
                                  OrderLineRepository orderLineRepository,
                                  CoupangOrderLineRepository coupangOrderLineRepository,
                                  ElevenstOrderLineRepository elevenstOrderLineRepository,
                                  List<SettlementMirrorWriter> mirrorWriters) {
        this.settlementLineRepository = settlementLineRepository;
        this.productListingOptionRepository = productListingOptionRepository;
        this.orderLineRepository = orderLineRepository;
        this.coupangOrderLineRepository = coupangOrderLineRepository;
        this.elevenstOrderLineRepository = elevenstOrderLineRepository;
        mirrorWriters.forEach(writer -> this.mirrorWriters.put(writer.platform(), writer));
    }

    /**
     * Loads one page.
     *
     * @param seenKeysThisRun unique keys already seen in this run. 🔴 The caller makes it <b>per run</b> — a
     *                        correction re-read is another run and never hits it, so a hit only means the market
     *                        split one line in two (= the upsert overwrites the amount and silently loses one)
     * @return the page's counts (lines · matched · unmatched · duplicates)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertResult upsertPage(MarketplaceAccount account, List<SettlementLineDraft> page,
                                   Set<String> seenKeysThisRun) {
        int lines = 0;
        int matched = 0;
        int unmatched = 0;
        int duplicates = 0;

        for (SettlementLineDraft draft : page) {
            String key = draft.uniqueKey(account.getId());
            if (!seenKeysThisRun.add(key)) {
                // The same key twice in one sync run = the market split one line in two.
                // A correction re-read is another run and never lands here — only real duplicates do.
                duplicates++;
                log.warn("정산 라인 유일키 중복 — 덮어쓰기 발생: {}", key);
            }

            SettlementLine saved = upsertOne(account, draft);
            lines++;
            if (saved.getOrderLine() != null) {
                matched++;
            } else {
                unmatched++;
            }

            SettlementMirrorWriter mirror = mirrorWriters.get(account.getPlatform());
            if (mirror != null) {
                mirror.write(saved, draft.raw());
            }
        }
        return new UpsertResult(lines, matched, unmatched, duplicates);
    }

    private SettlementLine upsertOne(MarketplaceAccount account, SettlementLineDraft draft) {
        Optional<SettlementLine> found = settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        account.getId(), draft.externalOrderId(), draft.platformOptionId(),
                        draft.saleType(), draft.recognitionDate());

        if (found.isEmpty()) {
            OrderLine orderLine = matchOrderLine(account, draft);
            ProductListingOption option = matchOption(account, draft.platformOptionId(), orderLine);
            return settlementLineRepository.save(SettlementLine.builder()
                    .marketplaceAccount(account)
                    .productListingOption(option)
                    .orderLine(orderLine)
                    .externalOrderId(draft.externalOrderId())
                    .platformOptionId(draft.platformOptionId())
                    .saleType(draft.saleType())
                    .quantity(draft.quantity())
                    .saleAmount(draft.saleAmount())
                    .serviceFee(draft.serviceFee())
                    .serviceFeeVat(draft.serviceFeeVat())
                    .serviceFeeRatio(draft.serviceFeeRatio())
                    .couponAmount(draft.couponAmount())
                    .deliveryFeeAmount(draft.deliveryFeeAmount())
                    .settlementAmount(draft.settlementAmount())
                    .recognitionDate(draft.recognitionDate())
                    .saleDate(draft.saleDate())
                    .build());                       // settlementPayout = null (D5-2) — the payout load fills it
        }

        // Correction: only amounts · quantity · dates are updated.
        // 🔴 settlementPayout is left alone (wiping the attribution the payout load filled breaks reconciliation).
        // ⚠️ orderLine / option are filled "only when empty" — if a re-read pushed out a mapping already attached,
        //    a match broken by a manual WING edit would overwrite a good value ([[project_wing_edit_desync]]).
        SettlementLine existing = found.get();
        OrderLine orderLine = existing.getOrderLine() != null
                ? existing.getOrderLine()
                : matchOrderLine(account, draft);
        ProductListingOption option = existing.getProductListingOption() != null
                ? existing.getProductListingOption()
                : matchOption(account, draft.platformOptionId(), orderLine);

        return settlementLineRepository.save(existing.toBuilder()
                .productListingOption(option)
                .orderLine(orderLine)
                .quantity(draft.quantity())
                .saleAmount(draft.saleAmount())
                .serviceFee(draft.serviceFee())
                .serviceFeeVat(draft.serviceFeeVat())
                .serviceFeeRatio(draft.serviceFeeRatio())
                .couponAmount(draft.couponAmount())
                .deliveryFeeAmount(draft.deliveryFeeAmount())
                .settlementAmount(draft.settlementAmount())
                .saleDate(draft.saleDate())
                .build());
    }

    /**
     * Channel option match (D7): {@code platformOptionId} (= Coupang vendorItemId) →
     * {@link ProductListingOption#getPlatformOptionId()}. The same path 2609_22 · 2609_23 use — do not add another
     * lookup.
     *
     * <p>When nothing matches, the option of the <b>attached order line</b> is used — the order already knows its
     * option, so there is no reason to search the listings again.
     *
     * <p>11st (FEATURE_2610_20 / D6): only the option of the attached 11st order line. The global
     * {@code findByPlatformOptionId} lookup is never used for an 11st line — 11st listings leave that column empty,
     * so a match there could only be a Coupang option.
     */
    private ProductListingOption matchOption(MarketplaceAccount account, String platformOptionId,
                                             OrderLine orderLine) {
        if (account.getPlatform() == Platform.ELEVENST) {
            return orderLine == null ? null : orderLine.getProductListingOption();
        }
        ProductListingOption option =
                productListingOptionRepository.findByPlatformOptionId(platformOptionId).orElse(null);
        if (option != null) {
            return option;
        }
        return orderLine == null ? null : orderLine.getProductListingOption();
    }

    /**
     * 주문 라인 매칭 (D7). 실패는 예외가 아니라 {@code UNMATCHED} 다 — 라인마다 WARN 을 찍으면 로그가
     * 못 쓰게 되므로 여기서는 조용히 null 을 돌려주고, 호출자가 매칭률을 한 줄로 남긴다.
     */
    private OrderLine matchOrderLine(MarketplaceAccount account, SettlementLineDraft draft) {
        if (account.getPlatform() == Platform.ELEVENST) {
            return matchElevenstOrderLine(account, draft);
        }
        // 🔴 <b>주문번호 + 마켓 옵션번호로 바로 붙인다</b>(FEATURE_2609_34). 예전에는 등록된 채널 옵션을
        //    거쳐야만 붙어서, 아직 등록하지 않은 상품의 판매는 전부 미분류로 남았다 — prod 실측으로
        //    1,096건 중 31건(2.8%)만 붙었고, 이 경로로는 672건(61.3%)이 붙는다. 등록 여부와 무관한
        //    식별자(마켓이 준 주문번호·옵션번호)로 맞추는 것이 원래 맞는 축이다.
        List<CoupangOrderLine> mirrored = coupangOrderLineRepository
                .findByMarketplaceAccount_IdAndOrderIdRawAndVendorItemId(
                        account.getId(), draft.externalOrderId(), draft.platformOptionId());
        OrderLine direct = pickOne(mirrored);
        if (direct != null) {
            return direct;
        }

        // 폴백: 등록된 채널 옵션 경유(옛 경로). 마켓 주문이 우리 DB 에 없을 때만 여기까지 온다.
        ProductListingOption option =
                productListingOptionRepository.findByPlatformOptionId(draft.platformOptionId()).orElse(null);
        if (option == null) {
            return null;
        }
        List<OrderLine> candidates = orderLineRepository
                .findByExternalOrderIdAndListingOptionId(draft.externalOrderId(), option.getId());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 11st order line by (account, {@code ordNo}, {@code ordPrdSeq}) — the 11st order mirror's unique key
     * (FEATURE_2610_20 / D5). A refund row's option key is {@code ordPrdSeq-clmReqSeq}; the part before the first
     * {@code -} is the order line sequence.
     */
    private OrderLine matchElevenstOrderLine(MarketplaceAccount account, SettlementLineDraft draft) {
        String optionKey = draft.platformOptionId();
        int dash = optionKey.indexOf('-');
        String ordPrdSeq = dash < 0 ? optionKey : optionKey.substring(0, dash);
        return elevenstOrderLineRepository
                .findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(account.getId(), draft.externalOrderId(), ordPrdSeq)
                .map(ElevenstOrderLine::getOrderLine)
                .orElse(null);
    }

    /**
     * 후보 중 하나를 고른다.
     *
     * <p>⚠️ 후보가 여럿인 것은 합포장 분할로 <b>같은 주문·같은 옵션</b>이 여러 박스에 걸친 경우다 —
     * 어느 쪽을 골라도 상품은 같고, 금액은 정산 쪽 값을 쓰므로 오염되지 않는다. 매번 같은 것이 나오도록
     * id 가 작은 것을 고른다(고르지 않고 버리면 그 판매가 영영 미분류로 남는다).
     */
    private static OrderLine pickOne(List<CoupangOrderLine> mirrored) {
        return mirrored.stream()
                .map(CoupangOrderLine::getOrderLine)
                .filter(Objects::nonNull)
                .min(Comparator.comparing(OrderLine::getId))
                .orElse(null);
    }

    /**
     * 페이지/회차 집계.
     *
     * @param unmatched 매칭 실패 — <b>실패가 아니라 정상 상태</b>다(D7)
     * @param duplicates 같은 실행 내 유일키 중복. 0 이 아니면 스키마 조치가 필요하다(01 「구현 후 dev 검증」)
     */
    public record UpsertResult(int lines, int matched, int unmatched, int duplicates) {

        public static UpsertResult empty() {
            return new UpsertResult(0, 0, 0, 0);
        }

        public UpsertResult plus(UpsertResult other) {
            return new UpsertResult(lines + other.lines, matched + other.matched,
                    unmatched + other.unmatched, duplicates + other.duplicates);
        }
    }
}
