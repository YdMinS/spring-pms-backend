package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.ValidationException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductListingTagRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 채널 연결 해제 · 미전송 채널 삭제(FEATURE_2609_63 / 01).
 *
 * <p>틀릴 수 있는 자리는 넷뿐이다: ① 옵션 FK 를 안 지운다 ② <b>BOM 을 같이 지워버린다</b>(D3 위반)
 * ③ DRAFT·남의 마스터 셀을 받아준다 ④ 마켓에 등록된 셀을 지워버린다. 마켓 어댑터는 주입하지 않는다 —
 * 호출이 <b>없다</b>는 것이 설계다.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChannelLinkServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private GeneratedProductDataRepository generatedProductDataRepository;
    @Mock private ProductListingTagRevisionRepository productListingTagRevisionRepository;
    @InjectMocks private ChannelLinkServiceImpl service;

    private static final Long MASTER_ID = 1L;
    private static final Long OTHER_MASTER_ID = 2L;
    private static final Long LISTING_ID = 41L;

    private MasterProduct master(Long id) {
        return MasterProduct.builder().id(id).name("마스터" + id).build();
    }

    private ProductListing cell(Long masterId, String platformProductId, ListingStatus status) {
        return ProductListing.builder()
                .id(LISTING_ID).name("셀").status(status)
                .platformProductId(platformProductId)
                .masterProduct(masterId == null ? null : master(masterId))
                .build();
    }

    private ProductListingOption option(Long id, ProductListing listing, Long masterOptionId) {
        return ProductListingOption.builder()
                .id(id).productListing(listing).optionName("옵션" + id).active(true)
                .masterProductOption(masterOptionId == null ? null
                        : MasterProductOption.builder().id(masterOptionId).build())
                .build();
    }

    private void givenMasterAndListing(ProductListing listing) {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master(MASTER_ID)));
        given(productListingRepository.findScopedById(LISTING_ID)).willReturn(Optional.of(listing));
    }

    // ---- unlink ----

    @Test
    @SuppressWarnings("unchecked")
    void unlink_registeredCell_clearsBothFks() {
        ProductListing listing = cell(MASTER_ID, "123", ListingStatus.SELLING);
        givenMasterAndListing(listing);
        given(productListingOptionRepository.findByProductListingId(LISTING_ID))
                .willReturn(List.of(option(71L, listing, 10L), option(72L, listing, null)));

        service.unlink(MASTER_ID, LISTING_ID);

        ArgumentCaptor<ProductListing> cellCaptor = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(cellCaptor.capture());
        assertThat(cellCaptor.getValue().getId()).isEqualTo(LISTING_ID);
        assertThat(cellCaptor.getValue().getMasterProduct()).isNull();

        ArgumentCaptor<List<ProductListingOption>> optionCaptor = ArgumentCaptor.forClass(List.class);
        verify(productListingOptionRepository).saveAll(optionCaptor.capture());
        assertThat(optionCaptor.getValue()).hasSize(2)
                .allSatisfy(option -> assertThat(option.getMasterProductOption()).isNull());
        // 해제는 FK 두 개만 비운다 — 마켓에 등록된 셀이라 가격·재고·승인상태는 그대로다.
        assertThat(optionCaptor.getValue()).allSatisfy(option -> assertThat(option.getActive()).isTrue());

    }

    @Test
    void unlink_cellOfAnotherMaster_throws() {
        givenMasterAndListing(cell(OTHER_MASTER_ID, "123", ListingStatus.SELLING));

        assertThatThrownBy(() -> service.unlink(MASTER_ID, LISTING_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessage("이 마스터의 채널이 아닙니다");
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    /** 🔴 D4: DRAFT 셀을 떼면 편입이 마켓 상품 ID 로 찾지 못해 어디에도 못 붙는 미아가 된다. */
    @Test
    void unlink_draftCell_throws() {
        givenMasterAndListing(cell(MASTER_ID, null, ListingStatus.DRAFT));

        assertThatThrownBy(() -> service.unlink(MASTER_ID, LISTING_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessage("마켓에 등록되지 않은 채널은 연결 해제할 수 없습니다");
        verify(productListingRepository, never()).save(any());
        verify(productListingOptionRepository, never()).saveAll(any());
    }

    // ---- DRAFT 채널 삭제 ----

    @Test
    void deleteDraftChannel_draftCell_deletesChildrenThenCell() {
        ProductListing listing = cell(MASTER_ID, null, ListingStatus.DRAFT);
        givenMasterAndListing(listing);

        service.deleteDraftChannel(MASTER_ID, LISTING_ID);

        // 🔴 순서가 규칙이다: 옵션 → 자동생성물 → 태그이력 → 셀(NOT NULL FK 라 자식이 먼저다).
        InOrder order = inOrder(productListingOptionRepository,
                generatedProductDataRepository, productListingTagRevisionRepository, productListingRepository);
        order.verify(productListingOptionRepository).deleteByProductListingId(LISTING_ID);
        order.verify(generatedProductDataRepository).deleteByProductListingId(LISTING_ID);
        order.verify(productListingTagRevisionRepository).deleteByProductListing_Id(LISTING_ID);
        order.verify(productListingRepository).delete(listing);
        // 🔴 flush 가 없으면 FK 위반이 커밋 시점(서비스 밖)에 터져 409 가 아니라 500 이 된다.
        order.verify(productListingRepository).flush();
    }

    /** D12: 마켓 상품 ID 만 본다 — 상태가 DRAFT 가 아니어도 마켓에 없는 셀은 지울 수 있어야 한다. */
    @Test
    void deleteDraftChannel_unregisteredNonDraftCell_deletes() {
        ProductListing listing = cell(MASTER_ID, null, ListingStatus.SELLING);
        givenMasterAndListing(listing);

        service.deleteDraftChannel(MASTER_ID, LISTING_ID);

        InOrder order = inOrder(productListingOptionRepository,
                generatedProductDataRepository, productListingTagRevisionRepository, productListingRepository);
        order.verify(productListingOptionRepository).deleteByProductListingId(LISTING_ID);
        order.verify(generatedProductDataRepository).deleteByProductListingId(LISTING_ID);
        order.verify(productListingTagRevisionRepository).deleteByProductListing_Id(LISTING_ID);
        order.verify(productListingRepository).delete(listing);
        order.verify(productListingRepository).flush();
    }

    /** 🔴 등록된 셀을 지우는 것이 이 조각의 최대 사고다 — 어떤 delete 도 호출되면 안 된다. */
    @Test
    void deleteDraftChannel_registeredCell_throws() {
        givenMasterAndListing(cell(MASTER_ID, "123", ListingStatus.SELLING));

        assertThatThrownBy(() -> service.deleteDraftChannel(MASTER_ID, LISTING_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessage("마켓에 등록된 채널은 삭제할 수 없습니다. 연결 해제 후 정리하세요");
        verify(productListingOptionRepository, never()).deleteByProductListingId(any());
        verify(generatedProductDataRepository, never()).deleteByProductListingId(any());
        verify(productListingTagRevisionRepository, never()).deleteByProductListing_Id(any());
        verify(productListingRepository, never()).delete(any());
    }
}
