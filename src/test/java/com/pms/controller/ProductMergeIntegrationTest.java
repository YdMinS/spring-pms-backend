package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.BoxRecipe;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.domain.ShoppingListItem;
import com.pms.domain.StockLocation;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product merge against a real database (FEATURE_2609_69 / B).
 *
 * <p>The unit tests prove the decisions; this one proves the things a mock cannot: that the bulk updates
 * really flush before the delete guard reads, that the {@code shopping_list_item} unique constraint is not
 * violated, and that the native {@code box_recipe} LIKE patterns hit only the source's own rows.</p>
 */
class ProductMergeIntegrationTest extends BaseIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderShipmentRepository orderShipmentRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private ShipmentParcelRepository shipmentParcelRepository;
    @Autowired private ShipmentParcelItemRepository shipmentParcelItemRepository;
    @Autowired private PurchaseRecordRepository purchaseRecordRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ShoppingListItemRepository shoppingListItemRepository;
    @Autowired private PriceChangeLogRepository priceChangeLogRepository;
    @Autowired private BoxRecipeRepository boxRecipeRepository;

    private Long targetId;
    private Long sourceId;
    private Seller seller;
    private OrderLine orderLine;
    private ShipmentParcel parcel;

    @BeforeEach
    void seed() {
        Product target = productRepository.save(Product.builder()
                .productName("파라마운트 팜즈 무염 피스타치오").brand("파라마운트").active(true).build());
        Product source = productRepository.save(Product.builder()
                .productName("원더풀 피스타치오").brand("원더풀").active(true).build());
        targetId = target.getId();
        sourceId = source.getId();

        seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(
                MarketplaceAccountFixture.coupangCoreBuilder().seller(seller).build());
        Order order = orderRepository.save(Order.builder()
                .marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-1").orderedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build());
        OrderShipment shipment = orderShipmentRepository.save(OrderShipment.builder()
                .order(order).externalShipmentId("BOX-1").build());
        orderLine = orderLineRepository.save(OrderLine.builder()
                .order(order).orderShipment(shipment).status(OrderStatus.PAID).itemName("피스타치오")
                .orderQty(1).cancelQty(0).holdQty(0).build());
        parcel = shipmentParcelRepository.save(ShipmentParcel.builder()
                .orderShipment(shipment).invoiceNumber("INV-1").parcelSeq(1)
                .status(ParcelStatus.PACKED).build());

        // history on the source: 2 purchases, 1 stock movement, 1 shipment item, 2 images, 1 price log
        purchaseRecordRepository.save(purchase(source, LocalDate.of(2026, 9, 1)));
        purchaseRecordRepository.save(purchase(source, LocalDate.of(2026, 9, 2)));
        stockMovementRepository.save(movement(source));
        shipmentParcelItemRepository.save(ShipmentParcelItem.builder()
                .shipmentParcel(parcel).orderLine(orderLine).product(source).quantity(1).build());
        productImageRepository.save(image(source, 0, "https://cdn/s0.jpg"));
        productImageRepository.save(image(source, 1, "https://cdn/s1.jpg"));
        priceChangeLogRepository.save(priceLog(source));

        // history already on the target: 1 image
        productImageRepository.save(image(target, 0, "https://cdn/t0.jpg"));
    }

    /**
     * ⚠️ Box memories survive the assertions and reference the seeded package, which the base teardown
     * deletes — a JUnit subclass {@code @AfterEach} runs first, so the children go before the parent row.
     */
    @AfterEach
    void clearBoxRecipes() {
        boxRecipeRepository.deleteAll();
    }

    private PurchaseRecord purchase(Product product, LocalDate day) {
        return PurchaseRecord.builder()
                .product(product).seller(seller).purchasedOn(day).quantity(1)
                .totalAmount(new BigDecimal("10000")).unitPrice(new BigDecimal("10000"))
                .reflectToBasePrice(false).build();
    }

    private StockMovement movement(Product product) {
        return StockMovement.builder()
                .product(product).seller(seller).movementType(StockMovementType.ADJUST).quantity(1)
                .location(StockLocation.OWN).movedOn(LocalDate.of(2026, 9, 3)).build();
    }

    private ProductImage image(Product product, int sortOrder, String url) {
        return ProductImage.builder().product(product).sortOrder(sortOrder).imageUrl(url).build();
    }

    private PriceChangeLog priceLog(Product product) {
        return PriceChangeLog.builder()
                .targetType(PriceTargetType.PRODUCT_COST).product(product)
                .oldPrice(new BigDecimal("9000")).newPrice(new BigDecimal("9500"))
                .reason(PriceChangeReason.MANUAL).build();
    }

    private String body(boolean shoppingListItems) {
        return """
                {
                  "targetProductId": %d,
                  "sourceProductId": %d,
                  "fields": {"productName": "원더풀 피스타치오 1kg", "barcodeId": "014113950374"},
                  "transfer": {
                    "purchaseRecords": true, "stockMovements": true, "shipmentItems": true,
                    "images": true, "shoppingListItems": %s, "priceChangeLogs": true,
                    "appendMemo": true
                  }
                }
                """.formatted(targetId, sourceId, shoppingListItems);
    }

    @Test
    void testMergeEndToEnd() throws Exception {
        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetProductId").value(targetId))
                .andExpect(jsonPath("$.data.moved.purchaseRecords").value(2))
                .andExpect(jsonPath("$.data.moved.images").value(2))
                .andExpect(jsonPath("$.data.snapshotFileName").exists());

        // every ticked table now points at the target and nothing is left on the source
        assertThat(purchaseRecordRepository.countByProductId(sourceId)).isZero();
        assertThat(purchaseRecordRepository.countByProductId(targetId)).isEqualTo(2);
        assertThat(stockMovementRepository.countByProductId(sourceId)).isZero();
        assertThat(stockMovementRepository.countByProductId(targetId)).isEqualTo(1);
        assertThat(shipmentParcelItemRepository.countByProductId(sourceId)).isZero();
        assertThat(shipmentParcelItemRepository.countByProductId(targetId)).isEqualTo(1);
        assertThat(priceChangeLogRepository.countByProductId(sourceId)).isZero();
        assertThat(priceChangeLogRepository.countByProductId(targetId)).isEqualTo(1);
        assertThat(productImageRepository.countByProductId(sourceId)).isZero();
        assertThat(productImageRepository.countByProductId(targetId)).isEqualTo(3);

        // the gallery was re-numbered, target-owned first
        assertThat(productImageRepository.findByProductIdOrderBySortOrderAsc(targetId))
                .extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2);
        assertThat(productImageRepository.findByProductIdOrderBySortOrderAsc(targetId).get(0).getImageUrl())
                .isEqualTo("https://cdn/t0.jpg");

        Product target = productRepository.findById(targetId).orElseThrow();
        assertThat(target.getProductName()).isEqualTo("원더풀 피스타치오 1kg");
        assertThat(target.getBarcodeId()).isEqualTo("014113950374");
        assertThat(target.getBrand()).isEqualTo("파라마운트");           // not provided → kept
        assertThat(target.getDescription()).contains("원더풀 피스타치오");
        assertThat(productRepository.findById(sourceId).orElseThrow().getActive()).isFalse();
    }

    @Test
    void testMergeWithShoppingConflictEndToEnd() throws Exception {
        shoppingListItemRepository.save(shoppingRow(productRepository.findById(targetId).orElseThrow(), 2));
        shoppingListItemRepository.save(shoppingRow(productRepository.findById(sourceId).orElseThrow(), 3));

        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.droppedOnConflict.shoppingListItems").value(1));

        List<ShoppingListItem> rows = shoppingListItemRepository.findByProductId(targetId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAutoQty()).isEqualTo(2);      // the target's own quantity, never 5
        assertThat(shoppingListItemRepository.countByProductId(sourceId)).isZero();
    }

    private ShoppingListItem shoppingRow(Product product, int autoQty) {
        return ShoppingListItem.builder()
                .orderLine(orderLine).product(product).autoQty(autoQty).manualQty(0).build();
    }

    @Test
    void testMergeBoxRecipeDeleteHitsOnlyOwnRows() throws Exception {
        Package box = packageRepository.findById(seededPackageId).orElseThrow();
        boxRecipeRepository.save(recipe(sourceId + ":2", box));                    // head of the key
        boxRecipeRepository.save(recipe(targetId + ":1|" + sourceId + ":1", box)); // after a '|'
        // ⚠️ Both "must stay" keys are derived from sourceId: a hardcoded id (e.g. "12:") collides the day
        // H2's IDENTITY hands out that very number for the source and the test fails for the wrong reason.
        boxRecipeRepository.save(recipe("1" + sourceId + ":2", box));              // longer id, must stay
        boxRecipeRepository.save(recipe((sourceId + 1000) + ":" + sourceId, box)); // id as quantity, must stay

        mockMvc.perform(post("/api/products/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.droppedOnConflict.boxRecipes").value(2));

        assertThat(boxRecipeRepository.findAll()).extracting(BoxRecipe::getRecipeKey)
                .containsExactlyInAnyOrder("1" + sourceId + ":2", (sourceId + 1000) + ":" + sourceId);
    }

    private BoxRecipe recipe(String key, Package box) {
        return BoxRecipe.builder().recipeKey(key).boxPackage(box).useCount(1)
                .lastUsedAt(LocalDateTime.of(2026, 9, 1, 9, 0)).build();
    }
}
