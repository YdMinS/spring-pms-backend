package com.pms.service.listing;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.Seller;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.PurchaseListService;
import com.pms.service.price.RepricingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 셀 BOM(사본) 경로와 마스터 경유 경로가 <b>같은 결과</b>를 내는지 확인하는 안전망(FEATURE_2609_71 / 01).
 *
 * <p>🔴 픽스처는 이 테스트가 직접 만든다 — 운영 데이터에 기대지 않는다. 마스터 1개(옵션 2 · 물품 2) +
 * 그 마스터에 붙은 셀 1개(셀 BOM 을 같은 값으로 심는다) + 마스터 없는 셀 옵션 1개(셀 BOM 만 있다).</p>
 *
 * <p>⚠️ 채널 전용 옵션은 두 경로가 <b>다른 게 정상</b>이다(옛 경로엔 값이 있고 새 경로는 마스터가 없다).
 * 같기를 기대하는 대신 미매핑으로 잡히는지를 따로 본다.</p>
 */
class CellBomEquivalenceIntegrationTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private CarrierRateRepository carrierRates;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private CoupangOrderLineRepository coupangOrderLineRepository;

    @Autowired private CellBomResolver cellBomResolver;
    @Autowired private PurchaseListService purchaseListService;
    @Autowired private RepricingService repricingService;

    private Product water;      // 5000 원
    private Product softener;   // 3000 원
    private Seller seller;
    private ProductListingOption linkedA;       // 마스터 옵션 연결 (물품 2종)
    private ProductListingOption linkedB;       // 마스터 옵션 연결 (물품 1종)
    private ProductListingOption channelOnly;   // 마스터 없음 — 셀 BOM 만 있다

    @BeforeEach
    void seedFixture() {
        water = productRepository.save(Product.builder()
                .productName("생수 2L").brand("노브랜드").price(new BigDecimal("5000")).active(true).build());
        softener = productRepository.save(Product.builder()
                .productName("섬유유연제").brand("다우니").price(new BigDecimal("3000")).active(true).build());

        seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("쿠팡 본계정").isActive(true).build());

        // 가격 재계산이 필요로 하는 기준값: 표준 카테고리 → 쿠팡 매핑 → 수수료, 마진 프리셋, 택배·박스.
        Category standard = categoryRepository.save(Category.builder().name("생수").build());
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("C-1").name("생수")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(standard).platform(Platform.COUPANG).platformCategoryId("C-1")
                .platformCategory(platformCategory).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform(Platform.COUPANG).marginRate(new BigDecimal("0.2000")).build());

        Package box = packageRepository.findById(seededPackageId).orElseThrow();
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("생수 마스터").active(true).category(standard)
                .defaultDelivery(carrierRates.findById(seededCarrierRateId).orElseThrow())
                .defaultPackage(box).build());

        MasterProductOption masterA = masterProductOptionRepository.save(
                MasterProductOption.builder().masterProduct(master).name("2입+1").build());
        MasterProductOption masterB = masterProductOptionRepository.save(
                MasterProductOption.builder().masterProduct(master).name("단품").build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterA).product(water).quantity(2).build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterA).product(softener).quantity(1).build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(masterB).product(water).quantity(1).build());

        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .platform(Platform.COUPANG).name("쿠팡 셀").status(ListingStatus.SELLING)
                .platformProductId("SP-1").seller(seller).masterProduct(master).build());

        linkedA = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).masterProductOption(masterA).optionName("2입+1")
                .platformOptionId("V-1").sellingPrice(new BigDecimal("20000")).build());
        linkedB = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).masterProductOption(masterB).optionName("단품")
                .platformOptionId("V-2").sellingPrice(new BigDecimal("12000")).build());
        channelOnly = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(cell).optionName("채널 전용")
                .platformOptionId("V-3").sellingPrice(new BigDecimal("9000")).build());

        // 셀 BOM(사본) — 연결 옵션은 마스터와 같은 값, 채널 전용 옵션은 셀에만 있는 값.
        saveCellLine(linkedA, water, 2);
        saveCellLine(linkedA, softener, 1);
        saveCellLine(linkedB, water, 1);
        saveCellLine(channelOnly, softener, 5);
    }

    /**
     * 마스터가 택배·박스 FK 를 들고 있어 base 정리(package/carrier_rate 삭제)가 FK 에 막힌다 — 먼저 끊는다.
     * JUnit 은 서브클래스 @AfterEach 를 base 보다 먼저 실행한다.
     */
    @AfterEach
    void unlinkMasterDefaults() {
        masterProductRepository.findAll().forEach(master -> masterProductRepository.save(
                master.toBuilder().defaultDelivery(null).defaultPackage(null).build()));
        masterProductRepository.flush();
    }

    private void saveCellLine(ProductListingOption option, Product product, int quantity) {
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(option).product(product).quantity(quantity).build());
    }

    /** 옛 경로: 셀 BOM 행 → (물품 id → 수량). */
    private Map<Long, Integer> oldBom(ProductListingOption option) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        productListingProductRepository.findByProductListingOptionId(option.getId())
                .forEach(line -> map.put(line.getProduct().getId(), line.getQuantity()));
        return map;
    }

    /** 새 경로: 마스터 경유 → (물품 id → 수량). */
    private Map<Long, Integer> newBom(ProductListingOption option) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        cellBomResolver.forOption(option).lines()
                .forEach(line -> map.put(line.productId(), line.quantity()));
        return map;
    }

    @Test
    void testOldAndNewAgreeOnFixture() {
        for (ProductListingOption option : List.of(linkedA, linkedB, channelOnly)) {
            CellBomResolver.Bom resolved = cellBomResolver.forOption(option);
            if (resolved.unmapped()) {
                continue;   // 채널 전용은 비교 대상이 아니다 — 아래 테스트가 따로 본다
            }
            assertThat(newBom(option)).as("option %s", option.getOptionName()).isEqualTo(oldBom(option));
        }
        // 비교가 실제로 일어났는지 — 전부 unmapped 였다면 위 루프는 아무것도 검증하지 않는다.
        assertThat(newBom(linkedA)).containsExactlyInAnyOrderEntriesOf(
                Map.of(water.getId(), 2, softener.getId(), 1));
    }

    @Test
    void testChannelOnlyOptionIsUnmappedNotEmpty() {
        CellBomResolver.Bom resolved = cellBomResolver.forOption(channelOnly);

        assertThat(resolved.unmapped()).isTrue();
        assertThat(resolved.lines()).isEmpty();
        // 셀 BOM 에는 값이 있다 — 그 값을 빈 목록으로 뭉개지 않고 "알 수 없음"으로 구분한다.
        assertThat(oldBom(channelOnly)).containsEntry(softener.getId(), 5);
    }

    /** 구매목록: 결제완료 주문 1건이 옛 경로와 같은 (물품, 수량)으로 전개된다. */
    @Test
    void testPurchaseListUnchanged() {
        Order order = orderRepository.save(Order.builder()
                .marketplaceAccount(marketplaceAccountRepository.findAll().get(0))
                .platform(Platform.COUPANG).externalOrderId("O-1")
                .orderedAt(LocalDateTime.now()).build());
        OrderLine line = orderLineRepository.save(OrderLine.builder()
                .order(order).itemName("생수 2입+1").orderQty(3).cancelQty(0).holdQty(0)
                .status(OrderStatus.PAID).productListingOption(linkedA).build());
        coupangOrderLineRepository.save(CoupangOrderLine.builder()
                .orderLine(line).marketplaceAccount(order.getMarketplaceAccount())
                .shipmentBoxId("B-1").orderIdRaw("O-1").vendorItemId("V-1")
                .platformStatus("ACCEPT").build());

        purchaseListService.extract();
        List<PurchaseProductGroup> groups = purchaseListService.getList().items();

        Map<Long, Integer> needed = new LinkedHashMap<>();
        groups.forEach(group -> needed.put(group.productId(), group.neededQty()));
        Map<Long, Integer> expected = new LinkedHashMap<>();
        oldBom(linkedA).forEach((productId, quantity) -> expected.put(productId, quantity * 3));

        assertThat(needed).isEqualTo(expected);
    }

    /** 가격 재계산: 원가 합이 옛 경로와 같고, 채널 전용 옵션은 사유를 달고 빠진다. */
    @Test
    void testRepricingUnchanged() {
        RepricingCandidatesResponse response =
                repricingService.candidates(seller.getId(), Platform.COUPANG, RepricingService.Scope.ALL);

        Map<Long, RepricingCandidatesResponse.Row> rows = new LinkedHashMap<>();
        response.rows().forEach(row -> rows.put(row.optionId(), row));

        assertThat(rows.get(linkedA.getId()).costSum()).isEqualByComparingTo(expectedCostSum(linkedA));
        assertThat(rows.get(linkedB.getId()).costSum()).isEqualByComparingTo(expectedCostSum(linkedB));

        RepricingCandidatesResponse.Row unmappedRow = rows.get(channelOnly.getId());
        assertThat(unmappedRow.costSum()).isNull();
        assertThat(unmappedRow.excluded()).isEqualTo(RepricingCandidatesResponse.Exclusion.UNCALCULABLE);
        assertThat(unmappedRow.excludedReason()).contains("마스터에 연결되지 않은");
    }

    /** 옛 경로로 계산한 Σ(물품가 × 수량). */
    private BigDecimal expectedCostSum(ProductListingOption option) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProductListingProduct line : productListingProductRepository
                .findByProductListingOptionId(option.getId())) {
            sum = sum.add(line.getProduct().getPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
        }
        return sum;
    }
}
