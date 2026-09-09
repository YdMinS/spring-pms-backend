package com.pms.service.stock;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OrderLine;
import com.pms.domain.ProductListingOption;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * 주문 라인 → 실물 물품 전개 (FEATURE_2609_28 / PLAN D13·D15).
 *
 * <p><b>BOM 전개를 아는 유일한 클래스</b>다. 출고 확인·구매목록처럼 "이 주문이 어떤 물품 몇 개를
 * 소진하는가"를 묻는 곳은 전부 여기를 통과한다 — 전개 규칙이 두 벌이 되면 화면마다 소진량이 갈린다.
 *
 * <pre>
 * OrderLine.productListingOption      (중립 링크, changeset 079)
 *   → ProductListingOption.masterProductOption   (nullable)
 *       → MasterProductOptionItem[]              (product × quantity)
 * </pre>
 *
 * <p>🔴 <b>정본은 마스터 BOM 이다</b>(D13). 채널 셀 BOM({@code ProductListingProduct})은 사본이고
 * 셀 전용 라인이 허용돼 채널마다 소진량이 갈릴 수 있다 — {@code OptionQuantitySync} javadoc 이 말하는
 * "마스터가 authoritative" 가 그 근거다. 이 클래스는 셀 BOM 리포지토리를 <b>주입받지 않는다</b>.
 *
 * <p>🔴 <b>전개 실패를 조용히 넘기지 않는다.</b> {@code continue} 로 삼키면 재고가 조용히 틀리지만,
 * 실패를 목록으로 돌려주면 <b>목록 누락</b>으로 드러나 사람이 보고 고칠 수 있다(D13). 실패 사유는
 * {@link Failure} 3종이고, 호출자는 그것을 화면까지 실어 보낸다.
 *
 * <p>⚠️ 수량 배수는 <b>호출자가 정한다</b>({@code unitCount}). 이 클래스가 {@code purchasableQty()} 를
 * 직접 부르면 반품 동기화가 올리는 {@code cancelQty} 가 출고 필요량을 저절로 깎는다
 * ({@code CoupangReturnSyncServiceImpl:369}) — 소진의 정본은 {@code STOCK_OUT} 합계다(D12).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderLineExpander {

    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;

    /** 전개 실패 사유 — 화면에 그대로 뜬다(사람이 고칠 수 있는 형태여야 한다). */
    public enum Failure {
        /** 라인이 어떤 채널 옵션에서 왔는지 모른다 (중립 링크 null + 거울 행 폴백도 실패). */
        UNMAPPED_OPTION,
        /** 채널 옵션은 찾았지만 마스터 옵션에 연결돼 있지 않다 (채널 전용 옵션). */
        NO_MASTER_OPTION,
        /** 마스터 옵션에 구성품(BOM)이 하나도 없다. */
        EMPTY_BOM
    }

    /** 전개된 물품 1건. {@code quantity} 는 이미 {@code unitCount} 가 곱해진 값이다. */
    public record ExpandedProduct(Long productId, String productName, int quantity) {}

    /** 라인 1건의 전개 결과. {@code failure != null} 이면 {@code products} 는 비어 있다. */
    public record LineExpansion(Long orderLineId, List<ExpandedProduct> products, Failure failure) {

        public boolean failed() {
            return failure != null;
        }

        static LineExpansion failed(Long orderLineId, Failure failure) {
            return new LineExpansion(orderLineId, List.of(), failure);
        }
    }

    /**
     * 라인 묶음을 전개한다. 반환 Map 은 입력 순서를 보존하고 <b>모든 라인에 대해 항목이 있다</b> —
     * 실패한 라인도 사유를 달고 들어 있다.
     *
     * @param lines     전개 대상 (같은 라인이 중복되면 마지막 것이 남는다)
     * @param unitCount 라인당 배수 — 출고는 {@code orderQty}, 구매목록은 {@code purchasableQty()}
     */
    public Map<Long, LineExpansion> expand(Collection<OrderLine> lines, ToIntFunction<OrderLine> unitCount) {
        if (lines == null || lines.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductListingOption> optionsByLine = resolveOptions(lines);
        Map<Long, List<MasterProductOptionItem>> bomByMasterOption = loadBoms(optionsByLine.values());

        Map<Long, LineExpansion> result = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            ProductListingOption option = optionsByLine.get(line.getId());
            if (option == null) {
                result.put(line.getId(), LineExpansion.failed(line.getId(), Failure.UNMAPPED_OPTION));
                continue;
            }
            MasterProductOption masterOption = option.getMasterProductOption();
            if (masterOption == null) {
                result.put(line.getId(), LineExpansion.failed(line.getId(), Failure.NO_MASTER_OPTION));
                continue;
            }
            List<MasterProductOptionItem> bom = bomByMasterOption.getOrDefault(masterOption.getId(), List.of());
            if (bom.isEmpty()) {
                result.put(line.getId(), LineExpansion.failed(line.getId(), Failure.EMPTY_BOM));
                continue;
            }

            int units = unitCount.applyAsInt(line);
            List<ExpandedProduct> products = bom.stream()
                    .map(item -> new ExpandedProduct(
                            item.getProduct().getId(),
                            item.getProduct().getProductName(),
                            item.getQuantity() * units))
                    .toList();
            result.put(line.getId(), new LineExpansion(line.getId(), products, null));
        }
        return result;
    }

    /**
     * 라인 → 채널 옵션. 중립 링크(D15)가 우선이고, 비어 있는 라인만 쿠팡 거울 행으로 <b>폴백</b>한다.
     *
     * <p>🔴 이 폴백이 {@code CoupangOrderLine} 을 읽는 <b>유일한 잔존 경로</b>다. 백필이 채우지 못한
     * 과거 라인을 위해 남겨 둔 것이며, 폴백이 실제로 쓰였다는 사실은 {@code log.debug} 로만 드러난다 —
     * 백필 누락을 발견하는 유일한 신호라 지우면 안 된다.
     */
    private Map<Long, ProductListingOption> resolveOptions(Collection<OrderLine> lines) {
        Map<Long, ProductListingOption> byLine = new HashMap<>();
        List<Long> needFallback = new ArrayList<>();
        for (OrderLine line : lines) {
            ProductListingOption option = line.getProductListingOption();
            if (option != null) {
                byLine.put(line.getId(), option);
            } else {
                needFallback.add(line.getId());
            }
        }
        if (needFallback.isEmpty()) {
            return byLine;
        }

        // 거울 행은 라인마다 다시 읽지 않고 한 번에 가져온다(N+1 금지).
        Map<Long, String> vendorItemIds = coupangOrderLineRepository.findByOrderLine_IdIn(needFallback).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), CoupangOrderLine::getVendorItemId,
                        (a, b) -> a));
        Map<String, ProductListingOption> cache = new HashMap<>();
        for (Long lineId : needFallback) {
            String vendorItemId = vendorItemIds.get(lineId);
            if (vendorItemId == null) {
                continue;   // 거울 행조차 없다 → UNMAPPED_OPTION
            }
            ProductListingOption option = cache.computeIfAbsent(vendorItemId,
                    key -> productListingOptionRepository.findByPlatformOptionId(key).orElse(null));
            if (option != null) {
                log.debug("order line {} expanded through the Coupang mirror fallback (vendorItemId={}) "
                        + "— its product_listing_option_id is still null", lineId, vendorItemId);
                byLine.put(lineId, option);
            }
        }
        return byLine;
    }

    /** 마스터 옵션 id 묶음 → BOM. 물품명을 쓰므로 product 를 fetch join 으로 함께 읽는다. */
    private Map<Long, List<MasterProductOptionItem>> loadBoms(Collection<ProductListingOption> options) {
        List<Long> masterOptionIds = options.stream()
                .map(ProductListingOption::getMasterProductOption)
                .filter(java.util.Objects::nonNull)
                .map(MasterProductOption::getId)
                .distinct()
                .toList();
        if (masterOptionIds.isEmpty()) {
            return Map.of();
        }
        return masterProductOptionItemRepository.findWithProductByOptionIdIn(masterOptionIds).stream()
                .collect(Collectors.groupingBy(item -> item.getOption().getId()));
    }
}
