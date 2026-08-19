package com.pms.service;

import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MasterProductImageServiceTest {

    @Mock private MasterProductImageRepository imageRepository;
    @Mock private MasterImageZoneAssignmentRepository assignmentRepository;
    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private MasterProductImageServiceImpl service;

    private static final Long MASTER_ID = 1L;
    private static final String ZONE_A = "product_photos";
    private static final String ZONE_B = "detail_photos";
    private static final String SOURCE = MasterImageZoneAssignment.SOURCE_ZONE;

    private MasterProduct master() {
        return MasterProduct.builder().id(MASTER_ID).name("m").active(true).build();
    }

    private MasterProductImage image(Long id, int sortOrder) {
        return MasterProductImage.builder()
                .id(id).masterProduct(master()).sortOrder(sortOrder).imageUrl("u" + id).build();
    }

    private MasterImageZoneAssignment mapping(MasterProductImage img, String zoneId, int sortOrder) {
        return MasterImageZoneAssignment.builder().image(img).zoneId(zoneId).sortOrder(sortOrder).build();
    }

    // ------------------------------------------------------------------ uploadToPool

    @Test
    void uploadToPool_nextSortOrder_isMaxPlusOne() {
        MultipartFile file = mockFile();
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), any())).willReturn("uploaded-url");
        // pool sortOrder [0, 2] → next = 3 (max+1, not size()=2)
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID))
                .willReturn(List.of(image(10L, 0), image(11L, 2)));
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.uploadToPool(MASTER_ID, file);

        ArgumentCaptor<MasterProductImage> c = ArgumentCaptor.forClass(MasterProductImage.class);
        verify(imageRepository).save(c.capture());
        assertThat(c.getValue().getSortOrder()).isEqualTo(3);
        assertThat(c.getValue().getImageUrl()).isEqualTo("uploaded-url");
        assertThat(c.getValue().getZoneId()).isNull(); // pool asset: no zone binding at upload
    }

    @Test
    void uploadToPool_emptyPool_sortOrderZero() {
        MultipartFile file = mockFile();
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), any())).willReturn("u");
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of());
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.uploadToPool(MASTER_ID, file);

        ArgumentCaptor<MasterProductImage> c = ArgumentCaptor.forClass(MasterProductImage.class);
        verify(imageRepository).save(c.capture());
        assertThat(c.getValue().getSortOrder()).isZero();
    }

    @Test
    void uploadToPool_missingMaster_404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.empty());
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class); // no stubs: fails before use
        assertThatThrownBy(() -> service.uploadToPool(MASTER_ID, file))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ setZoneImages

    @Test
    void setZoneImages_replacesMappingsInGivenOrder() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));
        given(assignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of());
        given(assignmentRepository.findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(MASTER_ID, ZONE_A))
                .willReturn(List.of());

        service.setZoneImages(MASTER_ID, ZONE_A, List.of(2L, 1L));

        // Old zone mappings removed, then reinserted in order via a single saveAll.
        verify(assignmentRepository).deleteByImage_MasterProductIdAndZoneId(MASTER_ID, ZONE_A);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MasterImageZoneAssignment>> c = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(c.capture());
        List<MasterImageZoneAssignment> saved = c.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getImage().getId()).isEqualTo(2L);
        assertThat(saved.get(0).getSortOrder()).isZero();
        assertThat(saved.get(1).getImage().getId()).isEqualTo(1L);
        assertThat(saved.get(1).getSortOrder()).isEqualTo(1);
    }

    @Test
    void setZoneImages_notInPool_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));

        assertThatThrownBy(() -> service.setZoneImages(MASTER_ID, ZONE_A, List.of(1L, 3L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setZoneImages_duplicateId_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));

        // [1,2,2]: distinct size differs → rejected before touching the pool (size pre-check).
        assertThatThrownBy(() -> service.setZoneImages(MASTER_ID, ZONE_A, List.of(1L, 2L, 2L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setZoneImages_sourceZone_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));

        assertThatThrownBy(() -> service.setZoneImages(MASTER_ID, SOURCE, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setZoneImages_emptyList_clearsZone() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of());
        given(assignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of());
        given(assignmentRepository.findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(MASTER_ID, ZONE_A))
                .willReturn(List.of());

        service.setZoneImages(MASTER_ID, ZONE_A, List.of());

        verify(assignmentRepository).deleteByImage_MasterProductIdAndZoneId(MASTER_ID, ZONE_A);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MasterImageZoneAssignment>> c = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(c.capture());
        assertThat(c.getValue()).isEmpty();
    }

    // ------------------------------------------------------------------ setSourceImage

    @Test
    void setSourceImage_deletesExistingThenInsertsOne() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage img = image(7L, 0);
        given(imageRepository.findById(7L)).willReturn(Optional.of(img));
        given(assignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(mapping(img, SOURCE, 0)));

        MasterProductImageResponse resp = service.setSourceImage(MASTER_ID, 7L);

        verify(assignmentRepository).deleteByImage_MasterProductIdAndZoneId(MASTER_ID, SOURCE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MasterImageZoneAssignment>> c = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(c.capture());
        assertThat(c.getValue()).hasSize(1);
        assertThat(c.getValue().get(0).getZoneId()).isEqualTo(SOURCE);
        assertThat(c.getValue().get(0).getSortOrder()).isZero();
        assertThat(resp.isSource()).isTrue();
    }

    @Test
    void setSourceImage_null_clearsOnly() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));

        MasterProductImageResponse resp = service.setSourceImage(MASTER_ID, null);

        assertThat(resp).isNull();
        verify(assignmentRepository).deleteByImage_MasterProductIdAndZoneId(MASTER_ID, SOURCE);
        verify(assignmentRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void setSourceImage_imageOfAnotherMaster_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage other = MasterProductImage.builder()
                .id(9L).masterProduct(MasterProduct.builder().id(999L).build())
                .sortOrder(0).imageUrl("x").build();
        given(imageRepository.findById(9L)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.setSourceImage(MASTER_ID, 9L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ removeFromPool

    @Test
    void removeFromPool_clearsMappingsThenRowThenStorage() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage img = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(img));

        service.removeFromPool(MASTER_ID, 5L);

        InOrder order = inOrder(assignmentRepository, imageRepository, imageStorageService);
        order.verify(assignmentRepository).deleteByImageId(5L);
        order.verify(imageRepository).delete(img);
        order.verify(imageStorageService).deleteImage("u5");
    }

    @Test
    void removeFromPool_storageFailure_stillDeletesRow() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage img = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(img));
        willThrow(new RuntimeException("s3 down")).given(imageStorageService).deleteImage("u5");

        service.removeFromPool(MASTER_ID, 5L); // must not propagate

        verify(assignmentRepository).deleteByImageId(5L);
        verify(imageRepository).delete(img);
    }

    @Test
    void removeFromPool_imageOfAnotherMaster_404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage other = MasterProductImage.builder()
                .id(9L).masterProduct(MasterProduct.builder().id(999L).build())
                .sortOrder(0).imageUrl("x").build();
        given(imageRepository.findById(9L)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.removeFromPool(MASTER_ID, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ reuse (core)

    @Test
    void listPool_oneImageReusedAcrossZonesAndSource() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage img = image(1L, 0);
        given(imageRepository.findByMasterProductIdOrderBySortOrderAsc(MASTER_ID)).willReturn(List.of(img));
        // Same asset mapped to zone A + zone B + __source__ (the whole point of the pool).
        given(assignmentRepository.findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(MASTER_ID))
                .willReturn(List.of(mapping(img, ZONE_A, 0), mapping(img, ZONE_B, 0), mapping(img, SOURCE, 0)));

        List<MasterProductImageResponse> pool = service.listPool(MASTER_ID);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).getAssignedZones()).containsExactlyInAnyOrder(ZONE_A, ZONE_B);
        assertThat(pool.get(0).isSource()).isTrue();
    }

    private MultipartFile mockFile() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        try {
            given(file.getBytes()).willReturn(new byte[]{1, 2, 3});
        } catch (Exception ignored) {
        }
        given(file.getContentType()).willReturn("image/jpeg");
        return file;
    }
}
