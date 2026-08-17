package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MasterProductImageServiceTest {

    @Mock private MasterProductImageRepository imageRepository;
    @Mock private MasterProductRepository masterProductRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private MasterProductImageServiceImpl service;

    private static final Long MASTER_ID = 1L;
    private static final String ZONE = "product_photos";

    private MasterProduct master() {
        return MasterProduct.builder().id(MASTER_ID).name("m").active(true).build();
    }

    private MasterProductImage image(Long id, int sortOrder) {
        return MasterProductImage.builder()
                .id(id).masterProduct(master()).zoneId(ZONE).sortOrder(sortOrder).imageUrl("u" + id).build();
    }

    // ------------------------------------------------------------------ upload

    @Test
    void upload_nextSortOrder_isMaxPlusOne() throws Exception {
        MultipartFile file = mockFile();
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), any())).willReturn("uploaded-url");
        // existing sortOrder [0, 2] → next = 3 (max+1, not size()=2)
        given(imageRepository.findByMasterProductIdAndZoneId(MASTER_ID, ZONE))
                .willReturn(List.of(image(10L, 0), image(11L, 2)));
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upload(MASTER_ID, ZONE, file);

        ArgumentCaptor<MasterProductImage> c = ArgumentCaptor.forClass(MasterProductImage.class);
        verify(imageRepository).save(c.capture());
        assertThat(c.getValue().getSortOrder()).isEqualTo(3);
        assertThat(c.getValue().getImageUrl()).isEqualTo("uploaded-url");
    }

    @Test
    void upload_emptyZone_sortOrderZero() throws Exception {
        MultipartFile file = mockFile();
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), any())).willReturn("u");
        given(imageRepository.findByMasterProductIdAndZoneId(MASTER_ID, ZONE)).willReturn(List.of());
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upload(MASTER_ID, ZONE, file);

        ArgumentCaptor<MasterProductImage> c = ArgumentCaptor.forClass(MasterProductImage.class);
        verify(imageRepository).save(c.capture());
        assertThat(c.getValue().getSortOrder()).isZero();
    }

    @Test
    void upload_missingMaster_404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.empty());
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class); // no stubs: fails before use
        assertThatThrownBy(() -> service.upload(MASTER_ID, ZONE, file))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ reorder

    @Test
    void reorder_reassignsSortOrderInGivenOrder() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdAndZoneId(MASTER_ID, ZONE))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));
        given(imageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(imageRepository.findByMasterProductIdAndZoneIdOrderBySortOrderAsc(MASTER_ID, ZONE))
                .willReturn(List.of(image(2L, 0), image(1L, 1)));

        service.reorder(MASTER_ID, ZONE, List.of(2L, 1L));

        ArgumentCaptor<MasterProductImage> c = ArgumentCaptor.forClass(MasterProductImage.class);
        verify(imageRepository, org.mockito.Mockito.times(2)).save(c.capture());
        // First saved = image 2 at position 0, second = image 1 at position 1.
        assertThat(c.getAllValues().get(0).getId()).isEqualTo(2L);
        assertThat(c.getAllValues().get(0).getSortOrder()).isZero();
        assertThat(c.getAllValues().get(1).getId()).isEqualTo(1L);
        assertThat(c.getAllValues().get(1).getSortOrder()).isEqualTo(1);
    }

    @Test
    void reorder_setMismatch_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdAndZoneId(MASTER_ID, ZONE))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));

        assertThatThrownBy(() -> service.reorder(MASTER_ID, ZONE, List.of(1L, 3L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reorder_duplicateId_400() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        given(imageRepository.findByMasterProductIdAndZoneId(MASTER_ID, ZONE))
                .willReturn(List.of(image(1L, 0), image(2L, 1)));

        // [1,2,2] over zone {1,2}: set equals but size differs → must be rejected (size pre-check).
        assertThatThrownBy(() -> service.reorder(MASTER_ID, ZONE, List.of(1L, 2L, 2L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ delete

    @Test
    void delete_removesStorageThenRow() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage img = image(5L, 0);
        given(imageRepository.findById(5L)).willReturn(Optional.of(img));

        service.delete(MASTER_ID, 5L);

        verify(imageStorageService).deleteImage("u5");
        verify(imageRepository).delete(img);
    }

    @Test
    void delete_imageOfAnotherMaster_404() {
        given(masterProductRepository.findScopedById(MASTER_ID)).willReturn(Optional.of(master()));
        MasterProductImage other = MasterProductImage.builder()
                .id(9L).masterProduct(MasterProduct.builder().id(999L).build())
                .zoneId(ZONE).sortOrder(0).imageUrl("x").build();
        given(imageRepository.findById(9L)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.delete(MASTER_ID, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
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
