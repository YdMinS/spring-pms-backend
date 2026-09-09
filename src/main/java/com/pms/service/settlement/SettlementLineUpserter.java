package com.pms.service.settlement;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SettlementLine;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementLineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SettlementLineDraft} → {@code settlement_line} 멱등 upsert + 주문 라인 매핑
 * (FEATURE_2609_30 / PLAN D7·D10).
 *
 * <p>⚠️ {@code REQUIRES_NEW} — 호출자({@code SettlementSyncServiceImpl})는 외부 HTTP 루프라 트랜잭션이
 * 없다. 페이지 1개 = 트랜잭션 1개여서 3페이지에서 실패해도 1~2페이지는 남는다
 * ({@code InquiryUpserter}·{@code ClaimUpserter} 와 같은 자세).
 *
 * <p>🔴 <b>delete-insert 금지</b>(D10). 정산은 확정 후 정정되므로 재조회가 잦은데, 지우고 다시 넣으면
 * {@code order_line} 매핑과 02 가 채운 지급 묶음 귀속이 함께 날아간다. 정정은 금액 필드 UPDATE 로만
 * 반영한다.
 */
@Slf4j
@Service
public class SettlementLineUpserter {

    private final SettlementLineRepository settlementLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final OrderLineRepository orderLineRepository;
    private final Map<Platform, SettlementMirrorWriter> mirrorWriters = new EnumMap<>(Platform.class);

    public SettlementLineUpserter(SettlementLineRepository settlementLineRepository,
                                  ProductListingOptionRepository productListingOptionRepository,
                                  OrderLineRepository orderLineRepository,
                                  List<SettlementMirrorWriter> mirrorWriters) {
        this.settlementLineRepository = settlementLineRepository;
        this.productListingOptionRepository = productListingOptionRepository;
        this.orderLineRepository = orderLineRepository;
        mirrorWriters.forEach(writer -> this.mirrorWriters.put(writer.platform(), writer));
    }

    /**
     * 페이지 1개를 적재한다.
     *
     * @param seenKeysThisRun 같은 실행에서 이미 본 유일키. 🔴 호출자가 <b>실행 단위</b>로 만들어 넘긴다 —
     *                        정정 재조회는 실행이 달라 여기 걸리지 않으므로, 여기 걸리는 것은 쿠팡이 한
     *                        라인을 쪼개 보냈다는 뜻(= upsert 가 금액을 덮어써 조용히 잃는 상황)뿐이다
     * @return 이 페이지의 집계 (라인·매칭·미매칭·중복)
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
                // 같은 동기화 실행에서 같은 키가 재등장 = 쿠팡이 한 라인을 쪼개 보냈다는 뜻이다.
                // 정정 재조회는 실행이 달라 여기 걸리지 않는다 — 진짜 중복만 잡힌다.
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
            ProductListingOption option = matchOption(draft.platformOptionId());
            return settlementLineRepository.save(SettlementLine.builder()
                    .marketplaceAccount(account)
                    .productListingOption(option)
                    .orderLine(matchOrderLine(draft.externalOrderId(), option))
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
                    .build());                       // settlementPayout = null (D5-2) — 02 가 채운다
        }

        // 정정 반영: 금액·수량·날짜만 갱신한다.
        // 🔴 settlementPayout 은 손대지 않는다(02 가 채운 귀속을 지우면 대사가 무너진다).
        // ⚠️ orderLine/option 은 "비어 있을 때만" 채운다 — 이미 붙은 매핑을 재조회가 밀어내면
        //    WING 수동 수정으로 깨진 매칭이 멀쩡한 값을 덮는다([[project_wing_edit_desync]]).
        SettlementLine existing = found.get();
        ProductListingOption option = existing.getProductListingOption() != null
                ? existing.getProductListingOption()
                : matchOption(draft.platformOptionId());
        OrderLine orderLine = existing.getOrderLine() != null
                ? existing.getOrderLine()
                : matchOrderLine(draft.externalOrderId(), option);

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
     * 채널 옵션 매칭 (D7): {@code platformOptionId}(= 쿠팡 vendorItemId) →
     * {@link ProductListingOption#getPlatformOptionId()}. 2609_22·2609_23 이 쓰는 경로 그대로다 —
     * 새 조회 방식을 만들지 말 것.
     */
    private ProductListingOption matchOption(String platformOptionId) {
        return productListingOptionRepository.findByPlatformOptionId(platformOptionId).orElse(null);
    }

    /**
     * 주문 라인 매칭 (D7). 실패는 예외가 아니라 {@code UNMATCHED} 다 — 라인마다 WARN 을 찍으면 로그가
     * 못 쓰게 되므로 여기서는 조용히 null 을 돌려주고, 호출자가 매칭률을 한 줄로 남긴다.
     */
    private OrderLine matchOrderLine(String externalOrderId, ProductListingOption option) {
        if (option == null) {
            return null;
        }
        List<OrderLine> candidates =
                orderLineRepository.findByExternalOrderIdAndListingOptionId(externalOrderId, option.getId());
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            // 합포장 분할 등으로 후보가 여러 개 — 틀린 라인에 붙이면 상품별 수익성이 오염된다.
            log.debug("Ambiguous order line for settlement: orderId={} optionId={} matches={}",
                    externalOrderId, option.getId(), candidates.size());
        }
        return null;
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
