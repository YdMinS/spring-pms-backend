package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailImageGroup;
import com.pms.domain.DetailTemplate;
import com.pms.dto.request.DetailImageGroupRequest;
import com.pms.dto.response.DetailImageGroupResponse;
import com.pms.repository.DetailImageGroupRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Detail image group catalog business logic (FEATURE_2609_03): code derivation, delete blocking by ACTIVE
 * templates only, and the mapping cleanup that rides along with a delete.
 */
@ExtendWith(MockitoExtension.class)
class DetailImageGroupServiceTest {

    @Mock private DetailImageGroupRepository detailImageGroupRepository;
    @Mock private DetailTemplateRepository detailTemplateRepository;
    @Mock private MasterImageZoneAssignmentRepository assignmentRepository;

    @InjectMocks private DetailImageGroupServiceImpl service;

    private DetailTemplate template(String name, boolean active, String... zoneCodes) {
        List<DetailBlock> blocks = java.util.Arrays.stream(zoneCodes)
                .map(code -> DetailBlock.builder().type("imageZone").bind(code).build())
                .toList();
        return DetailTemplate.builder()
                .id(1L).name(name).blocks(blocks).active(active).isDefault(false).build();
    }

    private DetailImageGroup group(Long id, String code, String name, int sortOrder) {
        return DetailImageGroup.builder().id(id).code(code).name(name).sortOrder(sortOrder).build();
    }

    private DetailImageGroupRequest request(String name) {
        return DetailImageGroupRequest.builder().name(name).build();
    }

    // ---- create ----

    @Test
    void create_duplicateName_throws() {
        given(detailImageGroupRepository.existsByName("제품 사진")).willReturn(true);

        assertThatThrownBy(() -> service.create(request("제품 사진")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 있는 이름입니다");
        verify(detailImageGroupRepository, never()).save(any());
    }

    @Test
    void create_derivesSlugCode_andNextSortOrder() {
        given(detailImageGroupRepository.existsByName(any())).willReturn(false);
        given(detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc())
                .willReturn(List.of(group(1L, "a", "A", 0), group(2L, "b", "B", 7)));
        given(detailImageGroupRepository.existsByCode(any())).willReturn(false);
        given(detailImageGroupRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailImageGroupResponse response = service.create(request("Detail Photos"));

        ArgumentCaptor<DetailImageGroup> captor = ArgumentCaptor.forClass(DetailImageGroup.class);
        verify(detailImageGroupRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("detail_photos");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(8); // max(7) + 1
        assertThat(response.getTemplateCount()).isZero();
        assertThat(response.getImageCount()).isZero();
    }

    @Test
    void create_nonAsciiName_fallsBackToZonePrefixedCode() {
        given(detailImageGroupRepository.existsByName(any())).willReturn(false);
        given(detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc()).willReturn(List.of());
        given(detailImageGroupRepository.existsByCode(any())).willReturn(false);
        given(detailImageGroupRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DetailImageGroupResponse response = service.create(request("연출컷"));

        assertThat(response.getCode()).startsWith("zone_");
        assertThat(response.getName()).isEqualTo("연출컷"); // the Korean name is what the UI shows
        assertThat(response.getSortOrder()).isZero();
    }

    // ---- delete ----

    @Test
    void delete_usedByActiveTemplate_throwsWithTemplateNames() {
        given(detailImageGroupRepository.findScopedById(1L))
                .willReturn(Optional.of(group(1L, "product_photos", "제품 사진", 0)));
        given(detailTemplateRepository.findAll())
                .willReturn(List.of(template("기본 상세 템플릿", true, "product_photos")));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기본 상세 템플릿");
        verify(assignmentRepository, never()).deleteByZoneIdScoped(any());
        verify(detailImageGroupRepository, never()).delete(any());
    }

    @Test
    void delete_noTemplateButPhotosMapped_succeedsAndCleansMappings() {
        DetailImageGroup group = group(1L, "product_photos", "제품 사진", 0);
        given(detailImageGroupRepository.findScopedById(1L)).willReturn(Optional.of(group));
        given(detailTemplateRepository.findAll()).willReturn(List.of());

        service.delete(1L);

        // Photo mappings never block the delete; they are cleaned up with it (the images themselves stay).
        verify(assignmentRepository).deleteByZoneIdScoped("product_photos");
        verify(detailImageGroupRepository).delete(group);
    }

    @Test
    void delete_usedOnlyByInactiveTemplate_succeeds() {
        DetailImageGroup group = group(1L, "product_photos", "제품 사진", 0);
        given(detailImageGroupRepository.findScopedById(1L)).willReturn(Optional.of(group));
        given(detailTemplateRepository.findAll())
                .willReturn(List.of(template("비활성 상세", false, "product_photos")));

        service.delete(1L);

        verify(detailImageGroupRepository).delete(group);
    }

    // ---- list ----

    @Test
    void list_countsActiveTemplatesOnly() {
        given(detailImageGroupRepository.findAllByOrderBySortOrderAscIdAsc())
                .willReturn(List.of(group(1L, "product_photos", "제품 사진", 0)));
        given(detailTemplateRepository.findAll()).willReturn(List.of(
                template("기본 상세 템플릿", true, "product_photos"),
                template("여름 상세", true, "product_photos"),
                template("비활성 상세", false, "product_photos")));
        given(assignmentRepository.countByZoneIdGrouped())
                .willReturn(List.<Object[]>of(new Object[]{"product_photos", 12L}));

        List<DetailImageGroupResponse> response = service.list();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getTemplateCount()).isEqualTo(2);
        assertThat(response.get(0).getUsedByTemplateNames())
                .containsExactly("기본 상세 템플릿", "여름 상세");
        assertThat(response.get(0).getImageCount()).isEqualTo(12);
    }

    // ---- rename ----

    @Test
    void rename_sameName_succeeds() {
        DetailImageGroup group = group(1L, "product_photos", "제품 사진", 0);
        given(detailImageGroupRepository.findScopedById(1L)).willReturn(Optional.of(group));
        // existsByNameAndIdNot excludes self, so saving an unchanged name must not 400.
        given(detailImageGroupRepository.existsByNameAndIdNot("제품 사진", 1L)).willReturn(false);
        given(detailImageGroupRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(detailTemplateRepository.findAll()).willReturn(List.of());
        given(assignmentRepository.countByZoneIdGrouped()).willReturn(List.of());

        DetailImageGroupResponse response = service.rename(1L, request("제품 사진"));

        assertThat(response.getName()).isEqualTo("제품 사진");
        assertThat(response.getCode()).isEqualTo("product_photos"); // code never changes
        assertThat(response.getSortOrder()).isZero();
    }
}
