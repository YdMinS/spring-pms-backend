package com.pms.service;

import com.pms.domain.OrderItem;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShoppingListItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PurchaseListServiceImpl 단위 테스트 — BOM 전개·멱등 추출·잔여 계산·미매핑·수동 누적의 핵심 로직만.
 * 프레임워크 검증(@NotNull 등)이나 단순 위임은 컨트롤러/통합 테스트로 미룬다.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseListServiceTest {

    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private PurchaseRecordRepository purchaseRecordRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private PurchaseListServiceImpl service;

    private Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).name(name).build();
    }

    private OrderItem acceptOrder(Long id, String optionId, int orderCount) {
        return OrderItem.builder()
                .id(id).platform("COUPANG").externalOrderId("O" + id).externalItemId(optionId)
                .itemName("주문" + id).orderCount(orderCount).cancelCount(0).holdCount(0)
                .status("ACCEPT").build();
    }

    @Test
    void extract_BOM전개_옵션당구성수량만큼_autoQty계산() {
        OrderItem oi = acceptOrder(10L, "OPT1", 3);          // 발주가능 3
        Product a = product(100L, "A");
        Product b = product(200L, "B");
        ProductListingOption option = ProductListingOption.builder().id(1L).platformOptionId("OPT1").build();

        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of(oi));
        given(productListingOptionRepository.findByPlatformOptionId("OPT1")).willReturn(Optional.of(option));
        given(productListingProductRepository.findByProductListingOptionId(1L)).willReturn(List.of(
                ProductListingProduct.builder().id(1L).product(a).quantity(2).build(),   // A×2 → 6
                ProductListingProduct.builder().id(2L).product(b).quantity(1).build()    // B×1 → 3
        ));
        given(shoppingListItemRepository.findByOrderItem_IdAndProduct_Id(anyLong(), anyLong()))
                .willReturn(Optional.empty());

        service.extract(null);

        verify(shoppingListItemRepository).resetAllAutoQty();
        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ShoppingListItem::getAutoQty)
                .containsExactly(6, 3);
    }

    @Test
    void extract_기존라인_manualQty보존_auto만갱신() {
        OrderItem oi = acceptOrder(10L, "OPT1", 3);          // 발주가능 3
        Product a = product(100L, "A");
        ProductListingOption option = ProductListingOption.builder().id(1L).platformOptionId("OPT1").build();
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(5L).orderItem(oi).product(a).autoQty(0).manualQty(4).build();

        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of(oi));
        given(productListingOptionRepository.findByPlatformOptionId("OPT1")).willReturn(Optional.of(option));
        given(productListingProductRepository.findByProductListingOptionId(1L)).willReturn(List.of(
                ProductListingProduct.builder().id(1L).product(a).quantity(2).build()    // 3×2 = 6
        ));
        given(shoppingListItemRepository.findByOrderItem_IdAndProduct_Id(10L, 100L))
                .willReturn(Optional.of(existing));

        service.extract(null);

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getAutoQty()).isEqualTo(6);
        assertThat(captor.getValue().getManualQty()).isEqualTo(4);   // 보존
    }

    @Test
    void extract_옵션미매핑_save호출안함() {
        OrderItem oi = acceptOrder(10L, "UNKNOWN", 3);
        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of(oi));
        given(productListingOptionRepository.findByPlatformOptionId("UNKNOWN")).willReturn(Optional.empty());

        service.extract(null);

        verify(shoppingListItemRepository).resetAllAutoQty();
        verify(shoppingListItemRepository, never()).save(any());
    }

    @Test
    void getList_잔여계산_잔여있으면그룹포함() {
        Product a = product(100L, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderItem(acceptOrder(10L, "OPT1", 8)).product(a).autoQty(8).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByItem_IdIn(List.of(1L))).willReturn(List.of(
                PurchaseRecord.builder().id(1L).item(sli).purchasedOn(LocalDate.now()).quantity(5).build()
        ));
        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).neededQty()).isEqualTo(8);
        assertThat(res.items().get(0).purchasedQty()).isEqualTo(5);
        assertThat(res.items().get(0).remainingQty()).isEqualTo(3);
    }

    @Test
    void getList_잔여0이면그룹제외() {
        Product a = product(100L, "A");
        ShoppingListItem sli = ShoppingListItem.builder()
                .id(1L).orderItem(acceptOrder(10L, "OPT1", 5)).product(a).autoQty(5).manualQty(0).build();

        given(shoppingListItemRepository.findAll()).willReturn(List.of(sli));
        given(purchaseRecordRepository.findByItem_IdIn(List.of(1L))).willReturn(List.of(
                PurchaseRecord.builder().id(1L).item(sli).purchasedOn(LocalDate.now()).quantity(5).build()
        ));
        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).isEmpty();
    }

    @Test
    void getList_ACCEPT인데옵션미매핑_unmappedOrders집계() {
        OrderItem oi = acceptOrder(10L, "X", 5);
        given(shoppingListItemRepository.findAll()).willReturn(List.of());
        given(orderItemRepository.findByStatus("ACCEPT")).willReturn(List.of(oi));
        given(productListingOptionRepository.findByPlatformOptionId("X")).willReturn(Optional.empty());

        PurchaseListResponse res = service.getList(null);

        assertThat(res.items()).isEmpty();
        assertThat(res.unmappedOrders()).hasSize(1);
        assertThat(res.unmappedOrders().get(0).externalItemId()).isEqualTo("X");
        assertThat(res.unmappedOrders().get(0).purchasableQty()).isEqualTo(5);
        assertThat(res.unmappedOrders().get(0).orderCount()).isEqualTo(1);
    }

    @Test
    void addManual_기존수동라인존재_manualQty누적_새행안만듦() {
        Product a = product(100L, "A");
        ShoppingListItem existing = ShoppingListItem.builder()
                .id(7L).orderItem(null).product(a).autoQty(0).manualQty(4).build();
        given(shoppingListItemRepository.findByOrderItemIsNullAndProduct_Id(100L))
                .willReturn(Optional.of(existing));

        service.addManual(new ManualItemRequest(100L, 3));

        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemRepository).save(captor.capture());
        assertThat(captor.getValue().getManualQty()).isEqualTo(7);   // 4 + 3
        verify(productRepository, never()).findById(anyLong());      // 신규 product 조회 없음
    }
}
