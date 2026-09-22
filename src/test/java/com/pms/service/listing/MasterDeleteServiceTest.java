package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductImage;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.exception.BusinessException;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.MasterProductImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 마스터 물리 삭제(FEATURE_2609_72 / 01).
 *
 * <p>틀릴 수 있는 자리는 넷이다: ① 마켓에 등록된 셀이 있는데 지워버린다 ② 자식보다 마스터를 먼저 지워 FK 로
 * 터진다 ③ 사진(S3 파일)을 너무 일찍 지워 롤백 때 파일만 사라진다 ④ 상태가 DRAFT 가 아니라는 이유로 마켓에
 * 없는 셀을 못 지워 마스터가 영영 안 지워진다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MasterDeleteServiceTest {

    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private MasterProductComponentRepository componentRepository;
    @Mock private MasterProductOptionRepository optionRepository;
    @Mock private MasterProductOptionItemRepository optionItemRepository;
    @Mock private MasterProductImageRepository imageRepository;
    @Mock private MasterProductImageService masterProductImageService;
    @Mock private ChannelLinkService channelLinkService;
    @InjectMocks private MasterDeleteServiceImpl service;

    private static final Long MASTER_ID = 1L;

    private MasterProduct master() {
        return MasterProduct.builder().id(MASTER_ID).name("마스터A").active(true).build();
    }

    private ProductListing cell(Long id, String platformProductId, ListingStatus status) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG)
                .platformProductId(platformProductId).status(status).build();
    }

    private MasterProductOption option(Long id) {
        return MasterProductOption.builder().id(id).name("옵션" + id).build();
    }

    private void givenMaster() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
    }

    /** 자식 → 마스터 순서가 곧 FK 순서다. 사진이 자식 중 마지막 = S3 롤백 창 최소화(D6-a). */
    @Test
    void deleteMaster_noCells_deletesChildrenThenMaster() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(option(10L), option(11L)));
        given(componentRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(MasterProductComponent.builder().id(30L).build()));
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID))
                .willReturn(List.of(MasterProductImage.builder().id(20L).build()));

        service.deleteMaster(MASTER_ID);

        InOrder order = inOrder(optionItemRepository, optionRepository, componentRepository,
                masterProductImageService, masterProductRepository);
        order.verify(optionItemRepository).deleteByOptionId(10L);
        order.verify(optionItemRepository).deleteByOptionId(11L);
        order.verify(optionRepository).deleteAll(any());
        order.verify(componentRepository).deleteByMasterProductId(MASTER_ID);
        order.verify(masterProductImageService).removeFromPool(MASTER_ID, 20L);
        order.verify(masterProductRepository).delete(any(MasterProduct.class));
    }

    /** 셀은 옵션·구성·사진보다 먼저 사라져야 한다 — 셀 옵션이 마스터 옵션을 FK 로 문다. */
    @Test
    void deleteMaster_unregisteredCell_deletesCellBeforeOptions() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(cell(100L, null, ListingStatus.DRAFT)));
        given(optionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(option(10L)));
        given(componentRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of());

        service.deleteMaster(MASTER_ID);

        // 셀 자식 리포지토리는 주입 자체가 없다 — 그 삭제는 deleteDraftChannel 소유다(D5).
        InOrder order = inOrder(channelLinkService, optionItemRepository);
        order.verify(channelLinkService).deleteDraftChannel(MASTER_ID, 100L);
        order.verify(optionItemRepository).deleteByOptionId(10L);
    }

    /** 🔴 이 조각의 최대 사고 — 마켓에 등록된 셀이 있으면 아무것도 지워지면 안 된다. */
    @Test
    void deleteMaster_onMarketCell_throws409_andDeletesNothing() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(cell(100L, "CP-1", ListingStatus.SELLING)));

        assertThatThrownBy(() -> service.deleteMaster(MASTER_ID))
                .isInstanceOf(MasterProductInUseException.class);
        verify(masterProductRepository, never()).delete(any());
        verify(channelLinkService, never()).deleteDraftChannel(any(), any());
    }

    /** D4: 상태는 보지 않는다 — 마켓 상품 ID 가 없으면 어디서도 팔리지 않는다. */
    @Test
    void deleteMaster_cellWithoutPlatformIdButSelling_isDeleted() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(cell(100L, null, ListingStatus.SELLING)));
        given(optionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(componentRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of());

        assertThatCode(() -> service.deleteMaster(MASTER_ID)).doesNotThrowAnyException();
        verify(channelLinkService).deleteDraftChannel(MASTER_ID, 100L);
    }

    @Test
    void deleteMaster_mixedCells_countsOnlyOnMarketOnes() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(
                cell(100L, null, ListingStatus.DRAFT),
                cell(101L, "CP-1", ListingStatus.SELLING),
                cell(102L, "CP-2", ListingStatus.SELLING)));

        assertThatThrownBy(() -> service.deleteMaster(MASTER_ID))
                .isInstanceOf(MasterProductInUseException.class)
                .hasMessageContaining("2개");
    }

    @Test
    void deleteMaster_otherTenantOrMissing_throws404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMaster(MASTER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(channelLinkService, never()).deleteDraftChannel(any(), any());
        verify(optionRepository, never()).deleteAll(any());
        verify(componentRepository, never()).deleteByMasterProductId(any());
        verify(masterProductRepository, never()).delete(any());
    }

    /** 🔴 flush 가 없으면 FK 위반이 커밋 시점(서비스 밖)에 터져 409 가 아니라 500 이 된다(D9). */
    @Test
    void deleteMaster_fkViolationOnFlush_throws409() {
        givenMaster();
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(optionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(componentRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of());
        willThrow(new DataIntegrityViolationException("FK")).given(masterProductRepository).flush();

        assertThatThrownBy(() -> service.deleteMaster(MASTER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("기록이 남아 있어 삭제할 수 없습니다")
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
