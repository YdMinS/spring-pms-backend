package com.pms.service.sales;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.dto.response.ChannelSalesResponse;
import com.pms.dto.response.MonthlyChannelSales;
import com.pms.dto.response.PayoutAggregate;
import com.pms.dto.response.ProductProfitResponse;
import com.pms.dto.response.SalesLineGroup;
import com.pms.dto.response.SalesLineView;
import com.pms.dto.response.SellerSalesResponse;
import com.pms.repository.MarketplaceAccountFixedCostRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.sales.FixedCostCalculator.FixedCostResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link SalesStatsService} 구현 (FEATURE_2609_30 / 03).
 *
 * <p><b>구조</b>: DB 가 라인을 <b>계정 × 채널 옵션</b>까지 접어서 주고({@link SalesLineGroup}),
 * 이 클래스는 그 위에 세율(수수료 + 부가세)·배송비·상자비를 곱한 뒤 축(판매자/채널/상품)별로 다시 접는다.
 * 라인을 자바로 끌어오지 않으므로 주문이 늘어도 이 리포트의 비용은 팔린 옵션 수만큼만 늘어난다.
 *
 * <p>🔴 <b>추정 수수료의 출처는 판매가 엔진과 같은 한 곳</b>이다 —
 * {@code MasterChannelConfigService.resolvePlatformCategory(cell).getCommissionRate()}
 * ({@code PriceCalculator} · {@code SettlementDiffAnalyzer} 와 동일). 다른 데서 수수료를 꺼내면 판매가 추천과
 * 손익 리포트가 서로 다른 기준으로 말하게 된다. 여기에 <b>수수료 부가세</b>를 더한다(PLAN D19):
 * {@code estFee = 판매액 × commissionRate × (1 + feeVatRate)}.
 *
 * <p>🔴 <b>{@code Product.price} 를 읽지 않는다.</b> 원가는 {@code order_line.cost_basis}/{@code cost_amount}
 * 스냅샷(changeset 081)뿐이고, 스냅샷이 없는 옛 라인이 섞이면 {@code estNetProfit = null} ·
 * {@code costBasisReady = false} 로 둔다. 현재가로 메우면 원가를 고칠 때마다 과거 순이익이 소급 변동하고,
 * 그런 리포트는 아무도 믿지 않는다. 이 클래스에 {@code ProductRepository} 의존이 아예 없는 것이 그 보증이다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional(readOnly = true)} — 채널 설정 resolver 가 LAZY 연관(마스터·카테고리
 * ·매핑)을 타므로 트랜잭션 밖에서 부르면 {@code open-in-view=false} 환경에서 LazyInitializationException 이 난다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class SalesStatsServiceImpl implements SalesStatsService {

    /** 미분류 행의 표시명 — 서버가 소유한다(프론트가 지어내지 않게). */
    static final String UNCATEGORIZED_NAME = "미분류";

    private final OrderLineRepository orderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final SettlementPayoutRepository settlementPayoutRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MarketplaceAccountFixedCostRepository fixedCostRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final FixedCostCalculator fixedCostCalculator;
    private final BigDecimal feeVatRate;

    public SalesStatsServiceImpl(OrderLineRepository orderLineRepository,
                                 ProductListingOptionRepository productListingOptionRepository,
                                 SettlementPayoutRepository settlementPayoutRepository,
                                 MarketplaceAccountRepository marketplaceAccountRepository,
                                 MarketplaceAccountFixedCostRepository fixedCostRepository,
                                 MasterChannelConfigService masterChannelConfigService,
                                 FixedCostCalculator fixedCostCalculator,
                                 @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.orderLineRepository = orderLineRepository;
        this.productListingOptionRepository = productListingOptionRepository;
        this.settlementPayoutRepository = settlementPayoutRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.fixedCostRepository = fixedCostRepository;
        this.masterChannelConfigService = masterChannelConfigService;
        this.fixedCostCalculator = fixedCostCalculator;
        this.feeVatRate = feeVatRate == null ? BigDecimal.ZERO : feeVatRate;
    }

    // ── ① 판매자 ──────────────────────────────────────────────────────────

    @Override
    public List<SellerSalesResponse> summary(LocalDate from, LocalDate to, Long sellerId) {
        Period period = Period.of(from, to);
        List<MarketplaceAccount> accounts = marketplaceAccountRepository.findAllWithSeller(sellerId);
        Map<Long, PayoutAggregate> payouts = payouts(period, sellerId);
        // 🔴 채널 탭과 같은 helper 를 1회만 부른다 — 두 탭이 각자 계산하면 합계가 어긋난다.
        Map<Long, FixedCostResult> fixedCosts = fixedCostByAccount(period, sellerId, accountIds(accounts));

        // 판매자 행은 계정 목록으로 만든다 — 이 기간에 판 게 없어도 "받을 돈"은 있을 수 있다.
        Map<Long, String> sellerNames = new LinkedHashMap<>();
        Map<Long, Acc> salesBySeller = new HashMap<>();
        Map<Long, Acc> payoutBySeller = new HashMap<>();
        Map<Long, BigDecimal> fixedBySeller = new HashMap<>();
        for (MarketplaceAccount account : accounts) {
            Long owner = account.getSeller().getId();
            sellerNames.putIfAbsent(owner, account.getSeller().getSellerName());
            payoutBySeller.computeIfAbsent(owner, key -> new Acc())
                    .addPayout(payouts.get(account.getId()));
            // 🔴 판매자 단위로 임계를 다시 판정하지 않는다(D12) — 채널 판정 결과를 더하기만 한다.
            fixedBySeller.merge(owner,
                    fixedCosts.getOrDefault(account.getId(), FixedCostResult.zero()).amount(),
                    BigDecimal::add);
        }

        for (GroupSales sales : collect(period, sellerId)) {
            Long owner = sales.group().sellerId();
            sellerNames.putIfAbsent(owner, sales.group().sellerName());
            salesBySeller.computeIfAbsent(owner, key -> new Acc()).addSales(sales);
        }

        List<SellerSalesResponse> rows = new ArrayList<>();
        sellerNames.forEach((owner, name) -> {
            Acc sales = salesBySeller.getOrDefault(owner, new Acc());
            Acc payout = payoutBySeller.getOrDefault(owner, new Acc());
            BigDecimal fixedCost = fixedBySeller.getOrDefault(owner, BigDecimal.ZERO);
            rows.add(new SellerSalesResponse(owner, name,
                    scale(sales.grossSales), scale(sales.discount), sales.netQty, sales.holdQty,
                    scale(sales.estFee), netProfit(sales.profit(), fixedCost), sales.profitReady(),
                    scale(payout.pendingPayout), scale(fixedCost)));
        });
        rows.sort(Comparator.comparing(SellerSalesResponse::grossSales).reversed());
        return rows;
    }

    // ── ② 채널 ────────────────────────────────────────────────────────────

    @Override
    public List<ChannelSalesResponse> byChannel(LocalDate from, LocalDate to, Long sellerId) {
        Period period = Period.of(from, to);
        Map<Long, PayoutAggregate> payouts = payouts(period, sellerId);
        Map<Long, Acc> salesByAccount = new HashMap<>();
        for (GroupSales sales : collect(period, sellerId)) {
            salesByAccount.computeIfAbsent(sales.group().accountId(), key -> new Acc()).addSales(sales);
        }

        List<MarketplaceAccount> accounts = marketplaceAccountRepository.findAllWithSeller(sellerId);
        // 🔴 루프 앞에서 1회. 행마다 부르면 채널 수만큼 쿼리가 나간다.
        Map<Long, FixedCostResult> fixedCosts = fixedCostByAccount(period, sellerId, accountIds(accounts));

        List<ChannelSalesResponse> rows = new ArrayList<>();
        for (MarketplaceAccount account : accounts) {
            Acc sales = salesByAccount.getOrDefault(account.getId(), new Acc());
            PayoutAggregate payout = payouts.getOrDefault(account.getId(),
                    PayoutAggregate.empty(account.getId()));
            FixedCostResult fixedCost = fixedCosts.getOrDefault(account.getId(), FixedCostResult.zero());
            rows.add(new ChannelSalesResponse(
                    account.getId(), account.getAccountAlias(), account.getPlatform(),
                    account.getSeller().getId(), account.getSeller().getSellerName(),
                    scale(sales.grossSales), scale(sales.discount), sales.netQty, sales.holdQty,
                    scale(sales.estFee), netProfit(sales.profit(), fixedCost.amount()), sales.profitReady(),
                    sales.cancelQty, scale(sales.refundedAmount), scale(sales.pendingRefundAmount),
                    scale(nz(payout.pendingPayout())), scale(nz(payout.paidAmount())),
                    account.getLastSettlementSyncAt(),
                    scale(fixedCost.amount()), fixedCost.chargedMonths()));
        }
        rows.sort(Comparator.comparing(ChannelSalesResponse::grossSales).reversed());
        return rows;
    }

    // ── ③ 상품 ────────────────────────────────────────────────────────────

    @Override
    public List<ProductProfitResponse> byProduct(LocalDate from, LocalDate to, Long sellerId,
                                                 boolean crossChannel) {
        Period period = Period.of(from, to);
        Map<Long, MarketplaceAccount> accounts = marketplaceAccountRepository.findAllWithSeller(sellerId)
                .stream().collect(Collectors.toMap(MarketplaceAccount::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

        // 집계 경로는 하나, 그룹 키만 바꾼다. 마스터가 없는 라인은 버리지 않고 `미분류` 한 행으로 모은다 —
        // 여기서 버리면 이 목록의 합계가 ①과 어긋나고, 어긋나는 순간 화면이 신뢰를 잃는다.
        Map<ProductKey, Acc> byKey = new LinkedHashMap<>();
        for (GroupSales sales : collect(period, sellerId)) {
            Long master = sales.group().masterProductId();
            Long account = crossChannel ? null : sales.group().accountId();
            byKey.computeIfAbsent(new ProductKey(master, account,
                            master == null ? UNCATEGORIZED_NAME : sales.group().masterProductName()),
                            key -> new Acc())
                    .addSales(sales);
        }

        List<ProductProfitResponse> rows = new ArrayList<>();
        byKey.forEach((key, acc) -> rows.add(new ProductProfitResponse(
                key.masterProductId(), key.name(),
                key.accountId(),
                key.accountId() == null ? null : alias(accounts, key.accountId()),
                acc.netQty, scale(acc.grossSales), scale(acc.discount), scale(acc.estFee),
                acc.profit(), acc.profitReady(),
                key.masterProductId() == null)));

        // 순이익 내림차순이 기본. 한 행이라도 원가 스냅샷이 없으면 정렬 기준 자체가 성립하지 않으므로
        // 매출액 내림차순으로 대체한다(섞어 정렬하면 null 행이 임의 위치로 튄다).
        boolean profitReady = !rows.isEmpty() && rows.stream().allMatch(ProductProfitResponse::costBasisReady);
        rows.sort(profitReady
                ? Comparator.comparing(ProductProfitResponse::estNetProfit).reversed()
                : Comparator.comparing(ProductProfitResponse::grossSales).reversed());
        return rows;
    }

    // ── ④ 판매 내역 ───────────────────────────────────────────────────────

    @Override
    public List<SalesLineView> lines(LocalDate from, LocalDate to, Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("판매 내역은 채널(accountId)을 지정해야 조회할 수 있습니다.");
        }
        Period period = Period.of(from, to);
        return orderLineRepository.findSalesLines(period.fromTime(), period.toExclusive(), accountId);
    }

    // ── 공통 집계 ─────────────────────────────────────────────────────────

    /**
     * 집계 그룹에 세율·배송비·상자비를 곱해 금액을 완성한다.
     *
     * <p>⚠️ 수수료율·배송비·상자비는 <b>셀 단위 캐시</b>로 한 번씩만 resolve 한다. 그룹마다 resolver 를
     * 부르면 (셀 → 마스터 → 카테고리 → 매핑) 경로가 그대로 N+1 이 된다.
     */
    private List<GroupSales> collect(Period period, Long sellerId) {
        List<SalesLineGroup> groups =
                orderLineRepository.aggregateSales(period.fromTime(), period.toExclusive(), sellerId);
        Map<Long, ProductListingOption> options = options(groups);

        Map<Long, BigDecimal> commissionCache = new HashMap<>();
        Map<Long, Shipping> shippingCache = new HashMap<>();
        List<GroupSales> result = new ArrayList<>(groups.size());
        for (SalesLineGroup group : groups) {
            ProductListingOption option = group.listingOptionId() == null
                    ? null : options.get(group.listingOptionId());
            result.add(measure(group, option, commissionCache, shippingCache));
        }
        return result;
    }

    private Map<Long, ProductListingOption> options(List<SalesLineGroup> groups) {
        Set<Long> ids = groups.stream()
                .map(SalesLineGroup::listingOptionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productListingOptionRepository.findWithConfigByIdIn(ids).stream()
                .collect(Collectors.toMap(ProductListingOption::getId, Function.identity(),
                        (left, right) -> left));
    }

    /**
     * 그룹 1건의 금액 계산.
     *
     * <pre>
     *   estFee       = grossSales × commissionRate × (1 + feeVatRate)        (D19)
     *   estNetProfit = grossSales − 원가 − estFee − 배송비×netQty − 상자비×netQty
     * </pre>
     *
     * <p>순이익은 <b>재료가 전부 있을 때만</b> 낸다: 원가 스냅샷이 빠진 라인이 없고(missingCostLines = 0),
     * 수수료 기준(카테고리 매핑)이 있고, 배송비·상자비가 해석될 때. 하나라도 없으면 {@code null} 이다 —
     * 없는 값을 0 으로 채우면 손익이 실제보다 좋아 보인다.
     */
    private GroupSales measure(SalesLineGroup group, ProductListingOption option,
                               Map<Long, BigDecimal> commissionCache, Map<Long, Shipping> shippingCache) {
        BigDecimal gross = nz(group.grossSales());
        ProductListing cell = option == null ? null : option.getProductListing();

        BigDecimal commissionRate = commissionRate(cell, commissionCache);
        BigDecimal estFee = commissionRate == null ? BigDecimal.ZERO
                : gross.multiply(commissionRate).multiply(BigDecimal.ONE.add(feeVatRate));

        Shipping shipping = shipping(cell, option, shippingCache);
        boolean ready = group.missingCostLines() == 0 && commissionRate != null && shipping != null;
        BigDecimal profit = null;
        if (ready) {
            BigDecimal qty = BigDecimal.valueOf(group.netQty());
            profit = gross.subtract(nz(group.costAmount())).subtract(estFee)
                    .subtract(shipping.total().multiply(qty));
        }
        return new GroupSales(group, gross, nz(group.discount()), group.netQty(), group.holdQty(),
                estFee, profit);
    }

    /**
     * 셀에 매핑된 카테고리의 수수료율. 매핑이 없으면 null 이다.
     *
     * <p>⚠️ 매핑 실패는 예외가 아니다 — 카테고리 시드 공백 하나 때문에 매출 화면 전체가 500 이 되면 안 된다.
     * 그 그룹은 추정 수수료 0 · 순이익 null 로 남고, {@code costBasisReady=false} 가 그것을 드러낸다.
     */
    private BigDecimal commissionRate(ProductListing cell, Map<Long, BigDecimal> cache) {
        if (cell == null) {
            return null;
        }
        return cache.computeIfAbsent(cell.getId(), key -> {
            try {
                return masterChannelConfigService.resolvePlatformCategory(cell).getCommissionRate();
            } catch (RuntimeException e) {
                log.debug("Sales stats: no commission base for listing={} ({})", key, e.getMessage());
                return null;
            }
        });
    }

    /**
     * 배송비 + 상자비 (옵션 override ?? 마스터 기본값) — 판매가 엔진과 같은 resolver 를 쓴다.
     *
     * <p>캐시 키가 <b>채널 옵션</b>인 이유: 배송비·상자비는 옵션 단위로 override 될 수 있어 셀 단위로
     * 캐시하면 다른 옵션의 값을 재사용하게 된다.
     */
    private Shipping shipping(ProductListing cell, ProductListingOption option,
                              Map<Long, Shipping> cache) {
        if (cell == null || option == null) {
            return null;
        }
        return cache.computeIfAbsent(option.getId(), key -> {
            MasterProductOption masterOption = option.getMasterProductOption();
            try {
                return new Shipping(
                        masterChannelConfigService.resolveDelivery(cell, masterOption).getCost(),
                        masterChannelConfigService.resolvePackage(cell, masterOption).getCost());
            } catch (RuntimeException e) {
                log.debug("Sales stats: no delivery/box base for option={} ({})", key, e.getMessage());
                return null;
            }
        });
    }

    // ── 고정비 (FEATURE_2609_33 / PLAN 2609_33 D4 · D4-1 · D6 · D12) ──────────────────────

    /**
     * 계정별 기간 고정비.
     *
     * <p>🔴 <b>{@code byChannel} 과 {@code summary} 가 이 메서드 하나만 쓴다.</b> 두 탭이 각자 계산하면
     * (a) 계정마다 쿼리가 나가고 (b) 두 탭 합계가 서로 어긋난다 — 어긋나는 순간 화면이 신뢰를 잃는다.
     *
     * <p>🔴 판정 매출은 <b>걸친 달 전체</b>다(D4-1): 조회 기간이 아니라 첫 달 1일 00:00 ~ 마지막 달
     * 다음 달 1일 00:00 으로 넓혀 집계한다. 9/1~9/10 조회에서 10일치로 판정하면 임계 미달로 보여
     * "9월엔 고정비가 없다" 가 된다.
     *
     * <p>⚠️ 연결이 <b>0건이면 월별 집계 쿼리를 아예 부르지 않는다</b> — 고정비를 쓰지 않는 테넌트가
     * 매출 화면을 열 때마다 집계를 한 번 더 돌 이유가 없다.
     */
    private Map<Long, FixedCostResult> fixedCostByAccount(Period period, Long sellerId,
                                                          Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<MarketplaceAccountFixedCost> links =
                fixedCostRepository.findByMarketplaceAccount_IdIn(accountIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MarketplaceAccountFixedCost>> linksByAccount = links.stream()
                .collect(Collectors.groupingBy(link -> link.getMarketplaceAccount().getId()));

        Map<Long, Map<String, BigDecimal>> monthlySales = monthlyNetSales(period, sellerId);
        Map<Long, FixedCostResult> result = new HashMap<>();
        linksByAccount.forEach((accountId, accountLinks) -> result.put(accountId,
                fixedCostCalculator.forChannel(accountLinks, period.from(), period.to(),
                        monthlySales.getOrDefault(accountId, Map.of()))));
        return result;
    }

    /** 계정 × {@code "YYYY-MM"} 할인 후 매출. 🔴 문자열 키 조립은 여기서만 한다(쿼리는 year/month 정수). */
    private Map<Long, Map<String, BigDecimal>> monthlyNetSales(Period period, Long sellerId) {
        LocalDateTime from = YearMonth.from(period.from()).atDay(1).atStartOfDay();
        LocalDateTime toExclusive = YearMonth.from(period.to()).plusMonths(1).atDay(1).atStartOfDay();
        Map<Long, Map<String, BigDecimal>> byAccount = new HashMap<>();
        for (MonthlyChannelSales row : orderLineRepository.aggregateMonthlySales(from, toExclusive, sellerId)) {
            byAccount.computeIfAbsent(row.accountId(), key -> new HashMap<>())
                    .merge(YearMonth.of(row.year(), row.month()).toString(), nz(row.netSales()),
                            BigDecimal::add);
        }
        return byAccount;
    }

    private static List<Long> accountIds(List<MarketplaceAccount> accounts) {
        return accounts.stream().map(MarketplaceAccount::getId).toList();
    }

    /**
     * 순이익에서 고정비를 뺀다 — 🔴 <b>profit 이 null 이 아닐 때만</b>(D6).
     *
     * <p>{@code costBasisReady == false} 면 순이익이 null 이라 뺄 대상이 없다. 그렇다고 고정비를 0 으로
     * 뭉개면 안 되므로 {@code fixedCost} 는 응답의 <b>자기 필드</b>로 따로 나간다.
     */
    private static BigDecimal netProfit(BigDecimal profit, BigDecimal fixedCost) {
        return profit == null ? null : profit.subtract(nz(fixedCost));
    }

    /**
     * 채널별 "받을 돈"·입금 확정. 🔴 {@code pendingPayout} 에는 기간이 걸리지 않는다(D4).
     *
     * <p>🔴 <b>대사 상태 건수는 여기서 내려보내지 않는다</b>(FEATURE_2609_34) — 기간이 걸리지 않는 건수를
     * 기간 행에 배지로 걸면 오해를 만든다. 대사 상태는 인식월 정산 목록이 건별로 보여준다.
     */
    private Map<Long, PayoutAggregate> payouts(Period period, Long sellerId) {
        return settlementPayoutRepository.aggregateByAccount(sellerId, period.from(), period.to(),
                        SettlementPayoutStatus.SCHEDULED, SettlementPayoutStatus.PAID).stream()
                .collect(Collectors.toMap(PayoutAggregate::accountId, Function.identity(),
                        (left, right) -> left));
    }

    private static String alias(Map<Long, MarketplaceAccount> accounts, Long accountId) {
        MarketplaceAccount account = accounts.get(accountId);
        return account == null ? null : account.getAccountAlias();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 배송비·상자비 한 쌍(셀 × 옵션 해석 결과). */
    private record Shipping(BigDecimal delivery, BigDecimal box) {
        BigDecimal total() {
            return nz(delivery).add(nz(box));
        }
    }

    /** 그룹 1건의 계산 결과. {@code estNetProfit == null} = 재료가 빠져 순이익을 낼 수 없는 그룹. */
    private record GroupSales(SalesLineGroup group, BigDecimal grossSales, BigDecimal discount,
                              long netQty, long holdQty, BigDecimal estFee, BigDecimal estNetProfit) {

        /** 취소·환불 값은 가공하지 않고 그대로 통과시킨다(DB 가 이미 접었다). */
        long cancelQty() {
            return group.cancelQty();
        }

        BigDecimal refundedAmount() {
            return nz(group.refundedAmount());
        }

        BigDecimal pendingRefundAmount() {
            return nz(group.pendingRefundAmount());
        }
    }

    /** ③의 그룹 키. {@code accountId == null} = 채널 교차(마스터 단위로 합침). */
    private record ProductKey(Long masterProductId, Long accountId, String name) {
    }

    /**
     * 축별 누산기.
     *
     * <p>🔴 {@code profitReady} 는 <b>AND</b> 다 — 한 그룹이라도 순이익을 못 내면 그 행의 순이익은 null 이다.
     * 일부만 더해 내보내면 "순이익 12만" 이 실제로는 상품 절반만 반영된 값이 되고, 그 사실이 화면에
     * 드러나지 않는다.
     */
    private static final class Acc {
        private BigDecimal grossSales = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal estFee = BigDecimal.ZERO;
        private BigDecimal estNetProfit = BigDecimal.ZERO;
        private BigDecimal pendingPayout = BigDecimal.ZERO;
        private BigDecimal refundedAmount = BigDecimal.ZERO;
        private BigDecimal pendingRefundAmount = BigDecimal.ZERO;
        private long netQty;
        private long holdQty;
        private long cancelQty;
        private boolean profitReady = true;

        void addSales(GroupSales sales) {
            grossSales = grossSales.add(sales.grossSales());
            discount = discount.add(sales.discount());
            estFee = estFee.add(sales.estFee());
            netQty += sales.netQty();
            holdQty += sales.holdQty();
            cancelQty += sales.cancelQty();
            refundedAmount = refundedAmount.add(sales.refundedAmount());
            pendingRefundAmount = pendingRefundAmount.add(sales.pendingRefundAmount());
            if (sales.estNetProfit() == null) {
                profitReady = false;
            } else {
                estNetProfit = estNetProfit.add(sales.estNetProfit());
            }
        }

        void addPayout(PayoutAggregate payout) {
            if (payout == null) {
                return;
            }
            pendingPayout = pendingPayout.add(nz(payout.pendingPayout()));
        }

        BigDecimal profit() {
            return profitReady ? scale(estNetProfit) : null;
        }

        boolean profitReady() {
            return profitReady;
        }
    }

    /**
     * 조회 기간. {@code to} 기본값 = 오늘, {@code from} 기본값 = 이번 달 1일.
     *
     * <p>⚠️ 상한은 <b>배타</b>({@code to + 1일 00:00})다. {@code 23:59:59} 로 자르면 그날 마지막 1초에
     * 들어온 주문이 조용히 빠진다.
     */
    record Period(LocalDate from, LocalDate to, LocalDateTime fromTime, LocalDateTime toExclusive) {

        static Period of(LocalDate from, LocalDate to) {
            LocalDate today = LocalDate.now();
            LocalDate end = to == null ? today : to;
            LocalDate start = from == null ? end.withDayOfMonth(1) : from;
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다");
            }
            return new Period(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        }
    }
}
