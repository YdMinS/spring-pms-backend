package com.pms.service.packing;

import com.pms.domain.BoxKind;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCarrierCode;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.PackingSavingsBoxRow;
import com.pms.dto.response.PackingSavingsOptionRow;
import com.pms.dto.response.PackingSavingsSummary;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.stock.OrderLineExpander;
import com.pms.service.stock.OrderLineExpander.ExpandedProduct;
import com.pms.service.stock.OrderLineExpander.LineExpansion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 포장 절약 집계 — 계산·배분·제외 (FEATURE_2609_41 / PLAN 2609_41 S1 ~ S16).
 *
 * <p>🔴 이 테스트가 지키는 함정 3가지:
 * <ul>
 *   <li>실린 배송비가 <b>NULL 이면 무료가 아니라 「모른다」</b>(S3) — 0 으로 읽으면 옛 주문에 없던 절약이 생긴다</li>
 *   <li>요율은 {@code is_default} 가 아니라 <b>포장일 기준 최신</b>으로 고른다(S15) — {@code is_default} 는
 *       시스템 전체에 1건이라 그것으로 거르면 대부분의 박스가 NULL 이 된다</li>
 *   <li>옵션별 배분은 <b>저장된 총액</b>을 나눌 뿐이다(S5) — 설정이 바뀌어도 합계가 움직이지 않는다</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PackingSavingsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final LocalDateTime PACKED_AT = LocalDateTime.of(2026, 9, 10, 14, 0);

    @Mock private ShipmentParcelRepository parcelRepository;
    @Mock private ShipmentParcelItemRepository parcelItemRepository;
    @Mock private CarrierRateRepository carrierRateRepository;
    @Mock private PlatformCarrierCodeRepository platformCarrierCodeRepository;
    @Mock private OrderLineExpander orderLineExpander;
    @Mock private MasterChannelConfigService masterChannelConfigService;

    @InjectMocks private PackingSavingsServiceImpl service;

    // ── 고정 그래프 ───────────────────────────────────────────────────────────

    private final Seller seller = Seller.builder().id(1L).sellerName("행복상회").build();
    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder()
            .id(10L).seller(seller).accountAlias("쿠팡-메인").build();
    private final Order order = Order.builder().id(100L).marketplaceAccount(account)
            .platform(Platform.COUPANG).externalOrderId("O-1")
            .orderedAt(LocalDateTime.of(2026, 8, 20, 9, 0)).build();
    private final ProductListing cell = ProductListing.builder().id(900L).build();
    private final MasterProduct master = MasterProduct.builder().id(500L).name("과자세트").build();
    private final Product product = Product.builder().id(700L).productName("과자").build();
    private final Carrier carrier = Carrier.builder().id(7L).name("CJ대한통운").isActive(true).build();

    // ── 1. 상자 절약 ─────────────────────────────────────────────────────────

    /** 상자 절약 = {@code expected_box_cost − actual_box_cost}(S2). 유료배송이라 택배 절약은 0 이다. */
    @Test
    void testBoxSavingIsExpectedMinusActual() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);
        OrderShipment shipment = shipment(200L, new BigDecimal("3000"));
        OrderLine line = line(300L, shipment, option, 1);
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "3000.00", "500.00", "2500.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, line, 1));
        givenExpansions(expansion(line, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        assertThat(summary.parcelCount()).isEqualTo(1);
        assertThat(summary.boxSaving()).isEqualByComparingTo("2500.00");
        assertThat(summary.deliverySaving()).isEqualByComparingTo("0.00");
        assertThat(summary.totalSaving()).isEqualByComparingTo("2500.00");
    }

    // ── 2·3. 택배 절약의 세 갈래 (S3 · S4 · S15) ──────────────────────────────

    /**
     * 🔴 S3 회귀: 실린 배송비가 <b>있으면</b>(유료배송) 택배 절약은 0 이다 — 받는 배송비도 한 건뿐이라 상쇄된다.
     * 무료배송 박스만 {@code expected_delivery_cost − 실제 1건분} 을 낸다.
     */
    @Test
    void testDeliverySavingOnlyWhenShippingFeeIsZero() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);

        OrderShipment paid = shipment(200L, new BigDecimal("3000"));
        OrderLine paidLine = line(300L, paid, option, 1);
        ShipmentParcel paidParcel = parcel(400L, paid, purchasedBox(), "3000.00", "500.00", "2500.00", "CJGLS");

        OrderShipment free = shipment(201L, BigDecimal.ZERO);
        OrderLine freeLine = line(301L, free, option, 1);
        ShipmentParcel freeParcel = parcel(401L, free, purchasedBox(), "3000.00", "500.00", "2500.00", "CJGLS");

        givenParcels(paidParcel, freeParcel);
        givenItems(item(1L, paidParcel, paidLine, 1), item(2L, freeParcel, freeLine, 1));
        givenExpansions(expansion(paidLine, 1), expansion(freeLine, 1));
        givenRateBook(rate(1L, "1800", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        // 무료배송 박스 한 건분만 = 2500 − 1800
        assertThat(summary.deliverySaving()).isEqualByComparingTo("700.00");
        assertThat(summary.missingShippingFeeCount()).isZero();
    }

    /** 🔴 S15 회귀: 요율 체인이 끊기면(플랫폼 코드 → 택배사 매핑 없음) 택배 절약은 <b>0 이 아니라 NULL</b> 이라 합계에서 빠진다. */
    @Test
    void testDeliverySavingNullWhenRateUnknown() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);
        OrderShipment shipment = shipment(200L, BigDecimal.ZERO);
        OrderLine line = line(300L, shipment, option, 1);
        // 매핑표에 없는 플랫폼 코드 = 2609_40 D6 이 코드를 못 되찾은 박스와 같은 자리다.
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "3000.00", "500.00", "2500.00", "NOSUCH");

        givenParcels(parcel);
        givenItems(item(1L, parcel, line, 1));
        givenExpansions(expansion(line, 1));
        givenRateBook(rate(1L, "1800", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        // 실제 택배비를 0 으로 읽었다면 2500.00 이 잡혔을 자리다.
        assertThat(summary.deliverySaving()).isEqualByComparingTo("0.00");
        assertThat(summary.boxSaving()).isEqualByComparingTo("2500.00");
        assertThat(summary.totalSaving()).isEqualByComparingTo("2500.00");
    }

    /**
     * 🔴 S3 회귀: 실린 배송비가 <b>NULL</b> 이면 무료배송으로 치지 않는다(changeset 066 이전 주문 = 「모른다」).
     * 택배 절약만 빠지고 상자 절약은 그대로 들어간다.
     */
    @Test
    void testDeliverySavingNullWhenShippingFeeIsNull() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);
        OrderShipment shipment = shipment(200L, null);
        OrderLine line = line(300L, shipment, option, 1);
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "3000.00", "500.00", "2500.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, line, 1));
        givenExpansions(expansion(line, 1));
        givenRateBook(rate(1L, "1800", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        assertThat(summary.missingShippingFeeCount()).isEqualTo(1);
        assertThat(summary.deliverySaving()).isEqualByComparingTo("0.00");   // 700.00 이 아니다
        assertThat(summary.boxSaving()).isEqualByComparingTo("2500.00");     // 상자 절약은 그대로
        assertThat(summary.missingBasisCount()).isZero();                    // 박스가 통째로 빠진 것은 아니다
    }

    /**
     * 🔴 S15 회귀: 요율 행은 {@code effective_date <= packed_at} 중 <b>가장 최근 1건</b>이다.
     * {@code is_default = false} 인 요율도 골라야 한다 — 그 값은 택배사별이 아니라 시스템 전체에 1건이라
     * {@code is_default} 로 거르면 대부분의 박스가 NULL 이 된다.
     */
    @Test
    void testCarrierRatePicksLatestBeforePackedAt() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);
        OrderShipment shipment = shipment(200L, BigDecimal.ZERO);
        OrderLine line = line(300L, shipment, option, 1);
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "3000.00", "500.00", "5000.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, line, 1));
        givenExpansions(expansion(line, 1));
        givenRateBook(
                rate(1L, "3500", LocalDate.of(2026, 5, 20), true),      // 옛 요율 + 시스템 기본
                rate(2L, "3000", LocalDate.of(2026, 9, 5), false),      // 🔴 골라야 하는 행
                rate(3L, "2800", LocalDate.of(2026, 10, 1), false));    // 포장일보다 미래
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        // 5000 − 3000. is_default 를 골랐다면 1500.00, 미래 요율이었다면 2200.00 이다.
        assertThat(summary.deliverySaving()).isEqualByComparingTo("2000.00");
    }

    // ── 4. 옵션별 배분 (S5 · S16) ────────────────────────────────────────────

    /** 🔴 옵션별 합계 == 전체. 반올림 잔돈은 가장 큰 몫(동점이면 옵션 id 작은 쪽)에 몰아준다(S5). */
    @Test
    void testOptionBreakdownSumsToTotal() {
        MasterProductOption first = masterOption(501L, "A");
        MasterProductOption second = masterOption(502L, "B");
        MasterProductOption third = masterOption(503L, "C");
        ProductListingOption optionA = option(11L, first);
        ProductListingOption optionB = option(12L, second);
        ProductListingOption optionC = option(13L, third);

        OrderShipment shipment = shipment(200L, BigDecimal.ZERO);
        OrderLine lineA = line(300L, shipment, optionA, 1);
        OrderLine lineB = line(301L, shipment, optionB, 1);
        OrderLine lineC = line(302L, shipment, optionC, 1);
        // 3000 − 800 = 2200 을 셋으로 나누면 733.33 × 3 = 2199.99 → 잔돈 0.01
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "3000.00", "800.00", "7500.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, lineA, 1), item(2L, parcel, lineB, 1), item(3L, parcel, lineC, 1));
        givenExpansions(expansion(lineA, 1), expansion(lineB, 1), expansion(lineC, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("1000", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);
        List<PackingSavingsOptionRow> rows = service.byOption(FROM, TO, null);

        assertThat(sum(rows, PackingSavingsOptionRow::boxSaving)).isEqualByComparingTo(summary.boxSaving());
        assertThat(sum(rows, PackingSavingsOptionRow::deliverySaving))
                .isEqualByComparingTo(summary.deliverySaving());
        assertThat(sum(rows, PackingSavingsOptionRow::totalSaving)).isEqualByComparingTo(summary.totalSaving());
        // 잔돈은 채널 옵션 id 가 가장 작은 11(= 마스터 옵션 501)에 붙는다
        assertThat(row(rows, 501L).boxSaving()).isEqualByComparingTo("733.34");
        assertThat(row(rows, 502L).boxSaving()).isEqualByComparingTo("733.33");
        assertThat(row(rows, 503L).boxSaving()).isEqualByComparingTo("733.33");
        // 🔴 행의 키는 마스터 옵션이고 마스터 상품을 함께 싣는다(S16)
        assertThat(row(rows, 501L).masterProductId()).isEqualTo(500L);
        assertThat(row(rows, 501L).masterProductName()).isEqualTo("과자세트");
    }

    /**
     * 🔴 S5 회귀: 포장 뒤 옵션 비용 설정이 바뀌어도 <b>박스 절약 합계는 저장값 그대로</b>다.
     * 가중치는 비율로만 쓰이므로 배분 비중만 달라진다 — 지나간 달의 절약이 오늘 설정에 따라 움직이면 안 된다.
     */
    @Test
    void testOptionBreakdownUsesStoredTotalNotRecomputed() {
        MasterProductOption first = masterOption(501L, "A");
        MasterProductOption second = masterOption(502L, "B");
        ProductListingOption optionA = option(11L, first);
        ProductListingOption optionB = option(12L, second);

        OrderShipment shipment = shipment(200L, new BigDecimal("3000"));
        OrderLine lineA = line(300L, shipment, optionA, 1);
        OrderLine lineB = line(301L, shipment, optionB, 1);
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "2000.00", "500.00", "5000.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, lineA, 1), item(2L, parcel, lineB, 1));
        givenExpansions(expansion(lineA, 1), expansion(lineB, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        // 포장 당시 상자비는 각 1000 이었지만 지금 설정은 전혀 다른 값을 돌려준다
        given(masterChannelConfigService.resolvePackage(any(), eq(first)))
                .willReturn(Package.builder().id(80L).cost(new BigDecimal("9000")).build());
        given(masterChannelConfigService.resolvePackage(any(), eq(second)))
                .willReturn(Package.builder().id(81L).cost(new BigDecimal("1000")).build());
        given(masterChannelConfigService.resolveDelivery(any(), any()))
                .willReturn(CarrierRate.builder().id(90L).cost(new BigDecimal("2500")).build());

        PackingSavingsSummary summary = service.summary(FROM, TO, null);
        List<PackingSavingsOptionRow> rows = service.byOption(FROM, TO, null);

        // 저장값 그대로 (2000 − 500). 오늘 설정으로 다시 계산했다면 10000 이 나왔을 자리다.
        assertThat(summary.boxSaving()).isEqualByComparingTo("1500.00");
        assertThat(sum(rows, PackingSavingsOptionRow::boxSaving)).isEqualByComparingTo("1500.00");
        // 비중만 바뀐다: 9000 : 1000
        assertThat(row(rows, 501L).boxSaving()).isEqualByComparingTo("1350.00");
        assertThat(row(rows, 502L).boxSaving()).isEqualByComparingTo("150.00");
    }

    // ── 5 ~ 7. 합계에 무엇이 들어가나 ─────────────────────────────────────────

    /** 🔴 S12: 비싼 상자를 쓴 박스(절약 음수)도 그대로 더한다 — 거르면 상자 선택이 영원히 교정되지 않는다. */
    @Test
    void testNegativeSavingIsIncluded() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);

        OrderShipment first = shipment(200L, new BigDecimal("3000"));
        OrderLine firstLine = line(300L, first, option, 1);
        ShipmentParcel good = parcel(400L, first, purchasedBox(), "1500.00", "500.00", "2500.00", "CJGLS");

        OrderShipment second = shipment(201L, new BigDecimal("3000"));
        OrderLine secondLine = line(301L, second, option, 1);
        ShipmentParcel expensive = parcel(401L, second, purchasedBox(), "500.00", "2000.00", "2500.00", "CJGLS");

        givenParcels(good, expensive);
        givenItems(item(1L, good, firstLine, 1), item(2L, expensive, secondLine, 1));
        givenExpansions(expansion(firstLine, 1), expansion(secondLine, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        assertThat(summary.parcelCount()).isEqualTo(2);
        assertThat(summary.negativeParcelCount()).isEqualTo(1);
        assertThat(summary.boxSaving()).isEqualByComparingTo("-500.00");   // 1000 + (−1500)
    }

    /** 🔴 S14: {@code expected_box_cost} 가 NULL 인 박스는 합계에 없고 건수에만 있다. 소리 없이 빼면 합계가 왜 작은지 아무도 모른다. */
    @Test
    void testMissingBasisExcludedButCounted() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);

        OrderShipment first = shipment(200L, new BigDecimal("3000"));
        OrderLine firstLine = line(300L, first, option, 1);
        ShipmentParcel measured = parcel(400L, first, purchasedBox(), "1500.00", "500.00", "2500.00", "CJGLS");

        OrderShipment second = shipment(201L, new BigDecimal("3000"));
        OrderLine secondLine = line(301L, second, option, 1);
        ShipmentParcel noBasis = parcel(401L, second, purchasedBox(), null, "500.00", null, "CJGLS");

        givenParcels(measured, noBasis);
        givenItems(item(1L, measured, firstLine, 1), item(2L, noBasis, secondLine, 1));
        givenExpansions(expansion(firstLine, 1), expansion(secondLine, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("500", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);
        List<PackingSavingsOptionRow> rows = service.byOption(FROM, TO, null);

        assertThat(summary.parcelCount()).isEqualTo(1);
        assertThat(summary.missingBasisCount()).isEqualTo(1);
        assertThat(summary.boxSaving()).isEqualByComparingTo("1000.00");
        // 🔴 summary 에서 뺀 박스는 options[] 에서도 빠져야 한다 — 두 숫자가 어긋나면 화면이 신뢰를 잃는다
        assertThat(sum(rows, PackingSavingsOptionRow::boxSaving)).isEqualByComparingTo("1000.00");
        assertThat(row(rows, 501L).parcelCount()).isEqualTo(1);
    }

    /** 재활용 상자로 합포장한 박스는 <b>양쪽에 모두</b> 들어간다 — 둘을 더하면 전체보다 커진다(응답 주석대로). */
    @Test
    void testRecycledAndConsolidatedMayOverlap() {
        MasterProductOption first = masterOption(501L, "A");
        MasterProductOption second = masterOption(502L, "B");
        ProductListingOption optionA = option(11L, first);
        ProductListingOption optionB = option(12L, second);

        OrderShipment shipment = shipment(200L, new BigDecimal("3000"));
        OrderLine lineA = line(300L, shipment, optionA, 1);
        OrderLine lineB = line(301L, shipment, optionB, 1);
        ShipmentParcel parcel = parcel(400L, shipment, recycledBox(), "2000.00", "0.00", "5000.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, lineA, 1), item(2L, parcel, lineB, 1));
        givenExpansions(expansion(lineA, 1), expansion(lineB, 1));
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("1000", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        assertThat(summary.recycledParcelCount()).isEqualTo(1);
        assertThat(summary.consolidatedParcelCount()).isEqualTo(1);
        assertThat(summary.recycledSaving()).isEqualByComparingTo("2000.00");
        assertThat(summary.consolidatedSaving()).isEqualByComparingTo("2000.00");
        assertThat(summary.recycledSaving().add(summary.consolidatedSaving()))
                .isGreaterThan(summary.totalSaving());
    }

    /**
     * 🔴 합포장 판정은 <b>옵션 가짓수가 아니라 수량</b>이다: 같은 옵션 2개를 한 박스에 담아도 판매가는
     * 상자·택배를 2개로 잡았으므로 절약이 난다. 옵션 2종 이상으로만 세면 그 절약이 통째로 안 보인다.
     */
    @Test
    void testConsolidationCountsSameOptionTwice() {
        MasterProductOption masterOption = masterOption(501L, "1박스");
        ProductListingOption option = option(11L, masterOption);
        OrderShipment shipment = shipment(200L, new BigDecimal("3000"));
        OrderLine line = line(300L, shipment, option, 2);          // 같은 옵션 2개 주문
        ShipmentParcel parcel = parcel(400L, shipment, purchasedBox(), "2000.00", "500.00", "5000.00", "CJGLS");

        givenParcels(parcel);
        givenItems(item(1L, parcel, line, 2));                     // 둘 다 한 박스에
        givenExpansions(expansion(line, 2));                       // BOM 1 × 주문 2
        givenRateBook(rate(1L, "3000", LocalDate.of(2026, 5, 20), true));
        givenCosts("1000", "2500");

        PackingSavingsSummary summary = service.summary(FROM, TO, null);

        assertThat(summary.consolidatedParcelCount()).isEqualTo(1);
        assertThat(summary.consolidatedSaving()).isEqualByComparingTo("1500.00");
    }

    // ── 8·9. 기간과 「기록 없음」 ─────────────────────────────────────────────

    /**
     * 🔴 S7: 기준일은 <b>포장 완료일</b>({@code packed_at})이다 — 매출 화면이 쓰는 주문일이 아니다.
     * 상한은 매출 쿼리와 같게 배타(다음 날 00:00)다.
     */
    @Test
    void testFiltersByPackedAtNotSaleDate() throws Exception {
        given(parcelRepository.findPackedBetween(any(), any(), any(), any())).willReturn(List.of());

        service.summary(FROM, TO, 42L);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toExclusive = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(parcelRepository).findPackedBetween(eq(ParcelStatus.PACKED), from.capture(),
                toExclusive.capture(), eq(42L));
        assertThat(from.getValue()).isEqualTo(FROM.atStartOfDay());
        assertThat(toExclusive.getValue()).isEqualTo(TO.plusDays(1).atStartOfDay());

        // 🔴 기간이 걸리는 컬럼 자체가 회귀 대상이다 — 주문일로 바뀌면 "이번 달에 아낀 돈"에 답할 수 없다
        String jpql = ShipmentParcelRepository.class
                .getMethod("findPackedBetween", ParcelStatus.class, LocalDateTime.class,
                        LocalDateTime.class, Long.class)
                .getAnnotation(Query.class).value();
        assertThat(jpql).contains("p.packedAt >= :from").contains("p.packedAt < :toExclusive");
        assertThat(jpql).doesNotContain("orderedAt");
    }

    /** 🔴 S1: 포장 화면을 안 거친 주문은 절약이 <b>0 이 아니라 행이 없다</b>. 0 으로 집계하면 모든 비율 지표가 거짓말이 된다. */
    @Test
    void testParcelWithoutRecordIsAbsentNotZero() {
        given(parcelRepository.findPackedBetween(any(), any(), any(), any())).willReturn(List.of());

        PackingSavingsSummary summary = service.summary(FROM, TO, null);
        List<PackingSavingsOptionRow> options = service.byOption(FROM, TO, null);
        List<PackingSavingsBoxRow> boxes = service.byBox(FROM, TO, null);

        assertThat(summary.parcelCount()).isZero();
        assertThat(summary.missingBasisCount()).isZero();
        assertThat(options).isEmpty();
        assertThat(boxes).isEmpty();
    }

    // ── 스텁 헬퍼 ────────────────────────────────────────────────────────────

    private void givenParcels(ShipmentParcel... parcels) {
        given(parcelRepository.findPackedBetween(any(), any(), any(), any())).willReturn(List.of(parcels));
    }

    private void givenItems(ShipmentParcelItem... items) {
        given(parcelItemRepository.findWithLineByParcelIdIn(any())).willReturn(List.of(items));
    }

    private void givenExpansions(LineExpansion... expansions) {
        Map<Long, LineExpansion> byLine = new LinkedHashMap<>();
        Arrays.stream(expansions).forEach(e -> byLine.put(e.orderLineId(), e));
        given(orderLineExpander.expand(any(), any())).willReturn(byLine);
    }

    private void givenRateBook(CarrierRate... rates) {
        given(platformCarrierCodeRepository.findAllWithCarrier()).willReturn(List.of(
                PlatformCarrierCode.builder().id(1L).carrier(carrier).platform(Platform.COUPANG)
                        .deliveryCompanyCode("CJGLS").build()));
        // 🔴 정렬은 서비스가 한다 — 리포지토리 순서에 기대면 DB 마다 결과가 달라진다
        given(carrierRateRepository.findAllWithCarrier()).willReturn(new ArrayList<>(List.of(rates)));
    }

    /** 배분 가중치에 쓰이는 옵션 비용(상자비·택배비). 🔴 총액이 아니라 <b>비율</b>에만 영향을 준다. */
    private void givenCosts(String boxCost, String deliveryCost) {
        given(masterChannelConfigService.resolvePackage(any(), any()))
                .willReturn(Package.builder().id(80L).cost(new BigDecimal(boxCost)).build());
        given(masterChannelConfigService.resolveDelivery(any(), any()))
                .willReturn(CarrierRate.builder().id(90L).cost(new BigDecimal(deliveryCost)).build());
    }

    // ── 엔티티 헬퍼 ──────────────────────────────────────────────────────────

    private MasterProductOption masterOption(Long id, String name) {
        return MasterProductOption.builder().id(id).masterProduct(master).name(name).build();
    }

    private ProductListingOption option(Long id, MasterProductOption masterOption) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName(masterOption.getName())
                .sellingPrice(new BigDecimal("19900")).masterProductOption(masterOption).build();
    }

    private OrderShipment shipment(Long id, BigDecimal shippingFee) {
        return OrderShipment.builder().id(id).order(order).externalShipmentId("S" + id)
                .shippingFee(shippingFee).build();
    }

    private OrderLine line(Long id, OrderShipment shipment, ProductListingOption option, int orderQty) {
        return OrderLine.builder().id(id).order(order).orderShipment(shipment)
                .productListingOption(option).itemName("과자세트 " + option.getOptionName())
                .orderQty(orderQty).cancelQty(0).holdQty(0).build();
    }

    private ShipmentParcel parcel(Long id, OrderShipment shipment, Package box, String expectedBox,
                                  String actualBox, String expectedDelivery, String carrierCode) {
        return ShipmentParcel.builder().id(id).orderShipment(shipment).invoiceNumber("INV-" + id)
                .carrierCode(carrierCode).carrierName("CJ대한통운").parcelSeq(1)
                .status(ParcelStatus.PACKED).packedAt(PACKED_AT).boxPackage(box)
                .expectedBoxCost(expectedBox == null ? null : new BigDecimal(expectedBox))
                .actualBoxCost(actualBox == null ? null : new BigDecimal(actualBox))
                .expectedDeliveryCost(expectedDelivery == null ? null : new BigDecimal(expectedDelivery))
                .build();
    }

    private ShipmentParcelItem item(Long id, ShipmentParcel parcel, OrderLine line, int quantity) {
        return ShipmentParcelItem.builder().id(id).shipmentParcel(parcel).orderLine(line)
                .product(product).quantity(quantity).build();
    }

    /** 그 라인이 소진하는 물품 수량(= BOM × 주문 수량) — 배분 비율의 분모다. */
    private LineExpansion expansion(OrderLine line, int requiredQty) {
        return new LineExpansion(line.getId(),
                List.of(new ExpandedProduct(product.getId(), product.getProductName(), requiredQty)), null);
    }

    private Package purchasedBox() {
        return Package.builder().id(60L).type("소형").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.of(2026, 1, 1)).isDefault(false).boxKind(BoxKind.PURCHASED).build();
    }

    private Package recycledBox() {
        return Package.builder().id(61L).type("재활용-중형").cost(BigDecimal.ZERO)
                .effectiveDate(LocalDate.of(2026, 1, 1)).isDefault(false).boxKind(BoxKind.RECYCLED).build();
    }

    private CarrierRate rate(Long id, String cost, LocalDate effectiveDate, boolean isDefault) {
        return CarrierRate.builder().id(id).carrier(carrier).type("표준")
                .cost(new BigDecimal(cost)).effectiveDate(effectiveDate).isDefault(isDefault).build();
    }

    // ── 단언 헬퍼 ────────────────────────────────────────────────────────────

    private BigDecimal sum(List<PackingSavingsOptionRow> rows,
                           java.util.function.Function<PackingSavingsOptionRow, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PackingSavingsOptionRow row(List<PackingSavingsOptionRow> rows, Long masterOptionId) {
        return rows.stream().filter(r -> r.masterOptionId().equals(masterOptionId)).findFirst().orElseThrow();
    }
}
