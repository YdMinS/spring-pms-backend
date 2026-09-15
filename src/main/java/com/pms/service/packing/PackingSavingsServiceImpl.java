package com.pms.service.packing;

import com.pms.domain.BoxKind;
import com.pms.domain.CarrierRate;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OrderLine;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCarrierCode;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.PackingSavingsBoxRow;
import com.pms.dto.response.PackingSavingsOptionRow;
import com.pms.dto.response.PackingSavingsSummary;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.OrderLineExpander;
import com.pms.service.stock.OrderLineExpander.ExpandedProduct;
import com.pms.service.stock.OrderLineExpander.LineExpansion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link PackingSavingsService} 구현 (FEATURE_2609_41 / PLAN 2609_41 S1 ~ S16).
 *
 * <p><b>한 박스의 계산</b>
 * <pre>
 *   상자 절약 = expected_box_cost − actual_box_cost                            (S2)
 *   택배 절약 = expected_delivery_cost − 실제 택배비 1건분                        (S4)
 *              ↑ 그 주문에 실제로 실린 배송비(order_shipment.shipping_fee)가 0 일 때만 (S3)
 * </pre>
 *
 * <p>🔴 <b>무료/유료 판정을 설정값({@code delivery_charge_type})으로 하지 않는다</b>(S3). 그것은 상품 설정이라
 * 나중에 바꾸면 과거 집계가 소급해서 변한다. 그리고 실린 배송비가 <b>{@code NULL} 이면 「무료」가 아니라
 * 「모른다」</b>다 — changeset 066(2026-09-07) 이전 주문은 값이 빈 채로 남았고(복구 불가), 0 으로 읽으면
 * 그 주문들에 <b>없던 절약이 생긴다</b>.
 *
 * <p>🔴 <b>저장된 박스 절약을 나눌 뿐, 총액을 다시 계산하지 않는다</b>(S5). 40 은 박스 단위 총액만 저장했고
 * (항목별 값이 없다) 옵션 구성이나 상자비·택배비 설정이 나중에 바뀌면 재계산값이 저장값과 달라진다 —
 * 그러면 지나간 달의 절약이 <b>오늘 설정에 따라 움직인다</b>. 가중치는 <b>비율로만</b> 쓰이므로 해석값이
 * 포장 당시와 달라도 합계는 저장값 그대로 남는다.
 *
 * <p>🔴 <b>가중치는 절약마다 따로 만든다</b>(S5): 상자 절약은 {@code resolvePackage(...).getCost()},
 * 택배 절약은 {@code resolveDelivery(...).getCost()} 다. 하나로 합치면 옵션마다 두 비용의 비율이 다를 때
 * 배분이 어긋난다.
 *
 * <p>🔴 <b>절약 0 과 「기록 없음」은 다른 값</b>이다(S1). 포장 화면을 안 거친 주문은 행이 아예 없고,
 * 계산 근거가 없는 박스({@code expected_box_cost} NULL)는 합계에서 빠지고 {@code missingBasisCount} 로만 센다(S14).
 *
 * <p>🔴 <b>손익 계산식을 건드리지 않는다</b>(S6) — {@code SalesStatsServiceImpl} 은 읽기만 했다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional(readOnly = true)}: 비용 resolver 가 LAZY 연관(셀 → 마스터 →
 * 옵션 override)을 타므로 {@code open-in-view=false} 환경에서 트랜잭션 밖이면 LazyInitializationException 이 난다
 * ({@code SalesStatsServiceImpl} 과 같은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackingSavingsServiceImpl implements PackingSavingsService {

    /** 금액 자릿수 — 저장된 절약과 같은 소수 2자리. */
    private static final int MONEY_SCALE = 2;

    /** 배분 비율의 중간 자릿수. 비율은 금액이 아니라 가중치라 넉넉히 잡는다. */
    private static final int RATIO_SCALE = 10;

    /**
     * 🔴 합포장 판정 기준 = 박스에 담긴 <b>주문 수량 합</b>(옵션 가짓수가 아니다). 같은 옵션 2개를 한 박스에
     * 담아도 판매가는 상자·택배를 2개로 잡았으므로 절약이 난다 — 옵션 2종 이상으로만 세면 그 절약이 통째로 안 보인다.
     * 2 대신 2−ε 인 이유: 수량 합은 나눗셈(담긴 수량 ÷ 필요 수량)의 누적이라 정확히 2 가 아니라 1.9999999998 로 떨어질 수 있다.
     */
    private static final BigDecimal CONSOLIDATION_UNITS = new BigDecimal("1.999999");

    private final ShipmentParcelRepository parcelRepository;
    private final ShipmentParcelItemRepository parcelItemRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PlatformCarrierCodeRepository platformCarrierCodeRepository;
    private final OrderLineExpander orderLineExpander;
    private final MasterChannelConfigService masterChannelConfigService;

    @Override
    public PackingSavingsSummary summary(LocalDate from, LocalDate to, Long sellerId) {
        return aggregate(from, to, sellerId).summary();
    }

    @Override
    public List<PackingSavingsOptionRow> byOption(LocalDate from, LocalDate to, Long sellerId) {
        return aggregate(from, to, sellerId).options();
    }

    @Override
    public List<PackingSavingsBoxRow> byBox(LocalDate from, LocalDate to, Long sellerId) {
        return aggregate(from, to, sellerId).boxes();
    }

    // ── 집계 ──────────────────────────────────────────────────────────────────

    private record Aggregate(PackingSavingsSummary summary,
                             List<PackingSavingsOptionRow> options,
                             List<PackingSavingsBoxRow> boxes) {
    }

    /**
     * 세 화면이 공유하는 단 하나의 계산.
     *
     * <p>⚠️ N+1 금지: 박스·내용물·요율·플랫폼 코드는 <b>각 1쿼리</b>로 읽고 메모리에서 묶는다. 비용 해석기만
     * 옵션 단위 캐시로 부른다({@code SalesStatsServiceImpl} 이 같은 이유로 쓰는 방식).
     */
    private Aggregate aggregate(LocalDate from, LocalDate to, Long sellerId) {
        Period period = Period.of(from, to);
        List<ShipmentParcel> parcels = parcelRepository.findPackedBetween(
                ParcelStatus.PACKED, period.fromTime(), period.toExclusive(), sellerId);
        if (parcels.isEmpty()) {
            return empty();
        }

        List<Long> parcelIds = parcels.stream().map(ShipmentParcel::getId).toList();
        List<ShipmentParcelItem> items = parcelItemRepository.findWithLineByParcelIdIn(parcelIds);
        Map<Long, List<ShipmentParcelItem>> itemsByParcel = items.stream()
                .collect(Collectors.groupingBy(item -> item.getShipmentParcel().getId()));

        // 🔴 필요 수량(= BOM × 주문 수량)은 기간 전체를 한 번에 편다 — 박스마다 부르지 않는다.
        //    StockOutService.remaining 을 쓰지 않는 이유: 같은 값을 주지만 출고 합계 쿼리가 딸려오고,
        //    여기서는 출고량이 필요 없다.
        Map<Long, OrderLine> linesById = items.stream()
                .map(ShipmentParcelItem::getOrderLine)
                .collect(Collectors.toMap(OrderLine::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, LineExpansion> expansions = orderLineExpander.expand(linesById.values(), OrderLine::getOrderQty);

        RateBook rates = rateBook();
        Map<Long, Costs> costCache = new HashMap<>();

        Totals totals = new Totals();
        Map<Long, RowAcc> optionRows = new LinkedHashMap<>();
        Map<Long, BoxAcc> boxRows = new LinkedHashMap<>();

        for (ShipmentParcel parcel : parcels) {
            if (parcel.getExpectedBoxCost() == null) {
                // S14: 계산 근거가 없는 박스 — 합계에서 빼고 건수만 센다. 소리 없이 빼면 합계가 왜 작은지 아무도 모른다.
                totals.missingBasisCount++;
                continue;
            }
            ParcelWeights weights = weightsOf(parcel, itemsByParcel.getOrDefault(parcel.getId(), List.of()),
                    expansions, costCache);
            if (weights == null) {
                // 🔴 40 이 전개 실패 라인의 포장을 막으므로 여기 오면 40 의 가정이 깨진 것이다. 조용히 빼지 않고
                //    summary 와 options[] 양쪽에서 똑같이 빼 두 숫자가 어긋나지 않게 한다(S14 와 같은 자리).
                log.warn("packing savings skipped parcel {} — its packed lines no longer resolve to a master option",
                        parcel.getId());
                totals.missingBasisCount++;
                continue;
            }

            BigDecimal boxSaving = money(parcel.getExpectedBoxCost().subtract(nz(parcel.getActualBoxCost())));
            boolean shippingFeeKnown = parcel.getOrderShipment().getShippingFee() != null;
            if (!shippingFeeKnown) {
                totals.missingShippingFeeCount++;
            }
            BigDecimal deliverySaving = deliverySaving(parcel, rates);

            totals.add(parcel, boxSaving, deliverySaving, weights.units());

            // ── 배분 (S5) ──
            Map<Long, BigDecimal> boxShares = allocate(boxSaving, weights.boxWeights());
            Map<Long, BigDecimal> deliveryShares = deliverySaving == null
                    ? Map.of() : allocate(deliverySaving, weights.deliveryWeights());

            Set<Long> masterOptionsInParcel = new LinkedHashSet<>();
            for (ProductListingOption option : weights.options().values()) {
                MasterProductOption masterOption = option.getMasterProductOption();
                RowAcc row = optionRows.computeIfAbsent(masterOption.getId(), key -> RowAcc.of(masterOption));
                row.boxSaving = row.boxSaving.add(boxShares.getOrDefault(option.getId(), BigDecimal.ZERO));
                row.deliverySaving = row.deliverySaving
                        .add(deliveryShares.getOrDefault(option.getId(), BigDecimal.ZERO));
                if (masterOptionsInParcel.add(masterOption.getId())) {
                    row.parcelCount++;
                }
            }

            Package box = parcel.getBoxPackage();
            if (box != null) {
                BoxAcc boxAcc = boxRows.computeIfAbsent(box.getId(), key -> BoxAcc.of(box));
                boxAcc.parcelCount++;
                boxAcc.boxSaving = boxAcc.boxSaving.add(boxSaving);
                boxAcc.deliverySaving = boxAcc.deliverySaving.add(nz(deliverySaving));
            }
        }

        return new Aggregate(totals.toSummary(), optionRowsOf(optionRows), boxRowsOf(boxRows));
    }

    private Aggregate empty() {
        return new Aggregate(new PackingSavingsSummary(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0, 0), List.of(), List.of());
    }

    // ── 택배 절약 (S3 · S4 · S15) ─────────────────────────────────────────────

    /**
     * 그 박스의 택배비 절약. <b>세 갈래를 한 칸도 합치지 않는다</b>(S3):
     *
     * <ul>
     *   <li>실린 배송비 {@code 0} = 무료배송 → {@code expected_delivery_cost − 실제 택배비 1건분}</li>
     *   <li>실린 배송비 {@code > 0} = 유료배송 → {@code 0} (받는 배송비도 한 건뿐이라 상쇄)</li>
     *   <li>🔴 실린 배송비 {@code NULL} = <b>모른다</b> → {@code null} (합계에서 빠지고 건수만 센다)</li>
     * </ul>
     *
     * <p>🔴 요율 체인이 한 군데서라도 끊기면 {@code null} 이다 — <b>0 이 아니다</b>(S4). 0 으로 두면
     * "절약이 없었다"로 읽히지만 실제로는 측정하지 못한 것이다.
     */
    private BigDecimal deliverySaving(ShipmentParcel parcel, RateBook rates) {
        BigDecimal shippingFee = parcel.getOrderShipment().getShippingFee();
        if (shippingFee == null) {
            return null;
        }
        if (shippingFee.signum() > 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal expected = parcel.getExpectedDeliveryCost();
        if (expected == null) {
            return null;
        }
        BigDecimal actual = rates.costOf(parcel);
        return actual == null ? null : money(expected.subtract(actual));
    }

    /**
     * 플랫폼 택배사 코드 → 내부 택배사 → 요율 (S15).
     *
     * <p>🔴 {@code is_default} 로 <b>거르지 않는다</b>: 그 값은 택배사별이 아니라 시스템 전체에 1건이라
     * ({@code CarrierRateRepository.findByIsDefaultTrue()} 가 단건이고 {@code CarrierRateServiceImpl} 이
     * 새 기본을 세울 때 기존 기본을 해제한다) 그것으로 고르면 기본을 가진 택배사 하나를 뺀 나머지 박스의
     * 택배 절약이 <b>전부 NULL</b> 이 된다. 같은 날짜가 여럿일 때의 <b>동점 처리</b>로만 쓴다.
     */
    private RateBook rateBook() {
        Map<String, Long> carrierByPlatformCode = new HashMap<>();
        for (PlatformCarrierCode code : platformCarrierCodeRepository.findAllWithCarrier()) {
            carrierByPlatformCode.put(platformCodeKey(code.getPlatform(), code.getDeliveryCompanyCode()),
                    code.getCarrier().getId());
        }
        Map<Long, List<CarrierRate>> ratesByCarrier = carrierRateRepository.findAllWithCarrier().stream()
                .collect(Collectors.groupingBy(rate -> rate.getCarrier().getId()));
        ratesByCarrier.values().forEach(list -> list.sort(
                Comparator.comparing(CarrierRate::getEffectiveDate).reversed()
                        .thenComparing(Comparator.comparing(
                                (CarrierRate rate) -> Boolean.TRUE.equals(rate.getIsDefault())).reversed())
                        .thenComparing(CarrierRate::getId)));
        return new RateBook(carrierByPlatformCode, ratesByCarrier);
    }

    private static String platformCodeKey(Platform platform, String deliveryCompanyCode) {
        return platform + "|" + deliveryCompanyCode.trim().toUpperCase();
    }

    /** 요율표 한 벌. 박스마다 조회하지 않기 위해 통째로 읽어 둔 것이다. */
    private record RateBook(Map<String, Long> carrierByPlatformCode,
                            Map<Long, List<CarrierRate>> ratesByCarrier) {

        /**
         * 그 박스에 실제로 든 택배비 1건분. 체인이 끊기면 {@code null}.
         *
         * <p>요율 행 = 그 택배사의 {@code effective_date <= packed_at} 인 것 중 <b>가장 최근 1건</b>
         * (같은 날짜가 여럿이면 {@code is_default = true} → {@code id} 작은 순).
         */
        BigDecimal costOf(ShipmentParcel parcel) {
            String platformCode = parcel.getCarrierCode();
            Platform platform = parcel.getOrderShipment().getOrder().getPlatform();
            LocalDateTime packedAt = parcel.getPackedAt();
            if (platformCode == null || platformCode.isBlank() || platform == null || packedAt == null) {
                // 🔴 carrier_code 가 NULL 인 박스(2609_40 D6 = 택배사 이름을 코드로 되찾지 못한 경우)도 여기다.
                return null;
            }
            Long carrierId = carrierByPlatformCode.get(platformCodeKey(platform, platformCode));
            if (carrierId == null) {
                return null;
            }
            LocalDate on = packedAt.toLocalDate();
            return ratesByCarrier.getOrDefault(carrierId, List.of()).stream()
                    .filter(rate -> !rate.getEffectiveDate().isAfter(on))
                    .findFirst()
                    .map(CarrierRate::getCost)
                    .orElse(null);
        }
    }

    // ── 옵션별 배분 (S5) ──────────────────────────────────────────────────────

    /** 한 박스의 가중치. 키는 <b>채널 옵션</b> id 다(비용이 채널 셀 × 옵션으로 해석되므로). */
    private record ParcelWeights(Map<Long, ProductListingOption> options,
                                 Map<Long, BigDecimal> boxWeights,
                                 Map<Long, BigDecimal> deliveryWeights,
                                 BigDecimal units) {
    }

    /**
     * 박스 1개의 옵션별 가중치.
     *
     * <pre>
     *   비율          = 그 박스에 담긴 그 라인의 수량 ÷ 그 라인의 필요 수량(BOM × 주문 수량)
     *   가중치(옵션)   = 비율 × 주문 수량 × <b>그 절약의</b> 옵션 비용
     * </pre>
     *
     * @return 담긴 라인 중 하나라도 마스터 옵션까지 내려가지 못하면 {@code null}(= 「근거 없음」)
     */
    private ParcelWeights weightsOf(ShipmentParcel parcel, List<ShipmentParcelItem> parcelItems,
                                    Map<Long, LineExpansion> expansions, Map<Long, Costs> costCache) {
        if (parcelItems.isEmpty()) {
            return null;
        }
        Map<Long, Integer> packedByLine = new LinkedHashMap<>();
        Map<Long, OrderLine> linesById = new LinkedHashMap<>();
        for (ShipmentParcelItem item : parcelItems) {
            OrderLine line = item.getOrderLine();
            linesById.putIfAbsent(line.getId(), line);
            packedByLine.merge(line.getId(), item.getQuantity(), Integer::sum);
        }

        Map<Long, ProductListingOption> options = new LinkedHashMap<>();
        Map<Long, BigDecimal> boxWeights = new LinkedHashMap<>();
        Map<Long, BigDecimal> deliveryWeights = new LinkedHashMap<>();
        BigDecimal units = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> entry : packedByLine.entrySet()) {
            OrderLine line = linesById.get(entry.getKey());
            ProductListingOption option = line.getProductListingOption();
            if (option == null || option.getMasterProductOption() == null) {
                return null;
            }
            int required = requiredQty(expansions.get(entry.getKey()));
            if (required <= 0) {
                return null;
            }
            BigDecimal ratio = BigDecimal.valueOf(entry.getValue())
                    .divide(BigDecimal.valueOf(required), RATIO_SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(line.getOrderQty()));
            units = units.add(ratio);

            Costs costs = costs(option, costCache);
            options.putIfAbsent(option.getId(), option);
            boxWeights.merge(option.getId(), ratio.multiply(costs.box()), BigDecimal::add);
            deliveryWeights.merge(option.getId(), ratio.multiply(costs.delivery()), BigDecimal::add);
        }
        return new ParcelWeights(options, boxWeights, deliveryWeights, units);
    }

    /** 그 라인이 소진하는 물품 수량 합 = BOM × 주문 수량. 전개 실패면 0 이다. */
    private int requiredQty(LineExpansion expansion) {
        if (expansion == null || expansion.failed()) {
            return 0;
        }
        return expansion.products().stream().mapToInt(ExpandedProduct::quantity).sum();
    }

    /**
     * 🔴 <b>저장된 박스 절약을 가중치 비율대로 나눈다</b> — 총액을 다시 계산하지 않는다(S5).
     *
     * <p>🔴 배분 합계는 박스 절약과 <b>정확히 같아야</b> 한다: 반올림 잔돈은 가장 큰 몫에 몰아준다
     * (같은 크기가 둘이면 <b>채널 옵션 id 가 작은 쪽</b> — 규칙이 없으면 테스트가 실행할 때마다 흔들린다).
     * 잔돈을 버리면 옵션별 합계와 전체가 어긋나고, 그 순간 화면 전체가 신뢰를 잃는다.
     *
     * <p>⚠️ 가중치 합이 0 이어도(비용 설정이 전부 0 이거나 해석이 끊긴 경우) 총액은 <b>사라지지 않는다</b> —
     * 모든 몫이 0 이 된 뒤 잔돈 규칙이 전액을 한 옵션에 싣는다.
     */
    private Map<Long, BigDecimal> allocate(BigDecimal total, Map<Long, BigDecimal> weights) {
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        if (weights.isEmpty()) {
            return shares;
        }
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map.Entry<Long, BigDecimal> entry : weights.entrySet()) {
            BigDecimal share = sum.signum() == 0 ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                    : total.multiply(entry.getValue()).divide(sum, MONEY_SCALE, RoundingMode.HALF_UP);
            shares.put(entry.getKey(), share);
        }
        BigDecimal remainder = total.subtract(
                shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        if (remainder.signum() != 0) {
            shares.merge(largestShare(shares), remainder, BigDecimal::add);
        }
        return shares;
    }

    private Long largestShare(Map<Long, BigDecimal> shares) {
        return shares.entrySet().stream()
                .max(Map.Entry.<Long, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
    }

    // ── 비용 해석 (옵션 단위 캐시) ────────────────────────────────────────────

    /** 가중치에 곱하는 옵션 비용. 🔴 상자 절약은 상자비로, 택배 절약은 택배비로 나눈다(S5). */
    private record Costs(BigDecimal box, BigDecimal delivery) {
    }

    /**
     * 옵션의 계산상 상자비·택배비. 캐시 키가 <b>채널 옵션</b>인 이유는 두 비용이 옵션 단위로 override 되기
     * 때문이다(셀 단위로 캐시하면 다른 옵션의 값을 재사용하게 된다).
     *
     * <p>⚠️ 해석에 실패해도 예외로 올리지 않는다 — 이 값은 <b>비율</b>로만 쓰이므로 합계는 저장값 그대로다.
     */
    private Costs costs(ProductListingOption option, Map<Long, Costs> cache) {
        return cache.computeIfAbsent(option.getId(), key -> {
            try {
                MasterProductOption masterOption = option.getMasterProductOption();
                return new Costs(
                        nz(masterChannelConfigService.resolvePackage(
                                option.getProductListing(), masterOption).getCost()),
                        nz(masterChannelConfigService.resolveDelivery(
                                option.getProductListing(), masterOption).getCost()));
            } catch (RuntimeException e) {
                log.debug("packing savings: no box/delivery base for option={} ({})", key, e.getMessage());
                return new Costs(BigDecimal.ZERO, BigDecimal.ZERO);
            }
        });
    }

    // ── 누적기 ────────────────────────────────────────────────────────────────

    /** 전체 합계 누적기. 🔴 재활용·합포장은 <b>겹칠 수 있다</b> — 둘을 더하면 전체보다 커진다. */
    private static final class Totals {
        private int parcelCount;
        private BigDecimal boxSaving = BigDecimal.ZERO;
        private BigDecimal deliverySaving = BigDecimal.ZERO;
        private int recycledParcelCount;
        private BigDecimal recycledSaving = BigDecimal.ZERO;
        private int consolidatedParcelCount;
        private BigDecimal consolidatedSaving = BigDecimal.ZERO;
        private int missingBasisCount;
        private int negativeParcelCount;
        private int missingShippingFeeCount;

        void add(ShipmentParcel parcel, BigDecimal boxSaving, BigDecimal deliverySaving, BigDecimal units) {
            BigDecimal parcelTotal = boxSaving.add(nz(deliverySaving));
            this.parcelCount++;
            this.boxSaving = this.boxSaving.add(boxSaving);
            this.deliverySaving = this.deliverySaving.add(nz(deliverySaving));
            Package box = parcel.getBoxPackage();
            if (box != null && box.getBoxKind() == BoxKind.RECYCLED) {
                this.recycledParcelCount++;
                this.recycledSaving = this.recycledSaving.add(parcelTotal);
            }
            if (units.compareTo(CONSOLIDATION_UNITS) >= 0) {
                this.consolidatedParcelCount++;
                this.consolidatedSaving = this.consolidatedSaving.add(parcelTotal);
            }
            if (parcelTotal.signum() < 0) {
                // 🔴 S12: 음수도 그대로 더한다. 거르면 "상자를 비싸게 쓴 것"이 영원히 안 보인다.
                this.negativeParcelCount++;
            }
        }

        PackingSavingsSummary toSummary() {
            return new PackingSavingsSummary(parcelCount, money(boxSaving), money(deliverySaving),
                    money(boxSaving.add(deliverySaving)), recycledParcelCount, money(recycledSaving),
                    consolidatedParcelCount, money(consolidatedSaving), missingBasisCount,
                    negativeParcelCount, missingShippingFeeCount);
        }
    }

    /** 옵션 행 누적기 — 키는 마스터 옵션이다(S16). */
    private static final class RowAcc {
        private Long masterProductId;
        private String masterProductName;
        private Long masterOptionId;
        private String masterOptionName;
        private int parcelCount;
        private BigDecimal boxSaving = BigDecimal.ZERO;
        private BigDecimal deliverySaving = BigDecimal.ZERO;

        static RowAcc of(MasterProductOption masterOption) {
            RowAcc acc = new RowAcc();
            MasterProduct master = masterOption.getMasterProduct();
            acc.masterProductId = master == null ? null : master.getId();
            acc.masterProductName = master == null ? null : master.getName();
            acc.masterOptionId = masterOption.getId();
            acc.masterOptionName = masterOption.getName();
            return acc;
        }

        PackingSavingsOptionRow toRow() {
            return new PackingSavingsOptionRow(masterProductId, masterProductName, masterOptionId,
                    masterOptionName, parcelCount, money(boxSaving), money(deliverySaving),
                    money(boxSaving.add(deliverySaving)));
        }
    }

    /** 상자 행 누적기 — 화면이 상자 그림을 그리도록 치수·사진을 함께 싣는다(2609_40 / 04). */
    private static final class BoxAcc {
        private Package box;
        private int parcelCount;
        private BigDecimal boxSaving = BigDecimal.ZERO;
        private BigDecimal deliverySaving = BigDecimal.ZERO;

        static BoxAcc of(Package box) {
            BoxAcc acc = new BoxAcc();
            acc.box = box;
            return acc;
        }

        PackingSavingsBoxRow toRow() {
            return new PackingSavingsBoxRow(box.getId(), box.getType(), box.getBoxKind(),
                    box.getWidthCm(), box.getLengthCm(), box.getHeightCm(), box.getImageUrl(),
                    parcelCount, money(boxSaving), money(deliverySaving),
                    money(boxSaving.add(deliverySaving)));
        }
    }

    private List<PackingSavingsOptionRow> optionRowsOf(Map<Long, RowAcc> rows) {
        List<PackingSavingsOptionRow> result = new ArrayList<>(rows.size());
        rows.values().forEach(acc -> result.add(acc.toRow()));
        result.sort(Comparator.comparing(PackingSavingsOptionRow::totalSaving).reversed()
                .thenComparing(PackingSavingsOptionRow::masterOptionId));
        return result;
    }

    private List<PackingSavingsBoxRow> boxRowsOf(Map<Long, BoxAcc> rows) {
        List<PackingSavingsBoxRow> result = new ArrayList<>(rows.size());
        rows.values().forEach(acc -> result.add(acc.toRow()));
        result.sort(Comparator.comparing(PackingSavingsBoxRow::totalSaving).reversed()
                .thenComparing(PackingSavingsBoxRow::packageId));
        return result;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 조회 기간. 기본값·상한 배타 규칙은 매출 화면과 같다 — 기간 고르는 법이 화면마다 다르면 그것만으로
     * 신뢰를 잃는다(S8).
     *
     * <p>🔴 이 기간이 걸리는 컬럼은 {@code packed_at} 이다(S7).
     */
    private record Period(LocalDateTime fromTime, LocalDateTime toExclusive) {

        static Period of(LocalDate from, LocalDate to) {
            LocalDate today = LocalDate.now();
            LocalDate end = to == null ? today : to;
            LocalDate start = from == null ? end.withDayOfMonth(1) : from;
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다");
            }
            return new Period(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        }
    }
}
