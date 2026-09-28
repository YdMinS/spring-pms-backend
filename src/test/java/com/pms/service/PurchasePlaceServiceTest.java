package com.pms.service;

import com.pms.domain.PurchasePlace;
import com.pms.dto.request.PurchasePlaceRequest;
import com.pms.dto.response.PurchasePlaceResponse;
import com.pms.repository.ProductPurchasePlaceRepository;
import com.pms.repository.PurchasePlaceRepository;
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
 * Purchase place list rules (FEATURE_2609_76): default seeding on an empty list (D19), duplicate names (D15),
 * next sort order, and the in-use delete guard (D9).
 */
@ExtendWith(MockitoExtension.class)
class PurchasePlaceServiceTest {

    @Mock private PurchasePlaceRepository purchasePlaceRepository;
    @Mock private ProductPurchasePlaceRepository productPurchasePlaceRepository;

    @InjectMocks private PurchasePlaceServiceImpl service;

    private PurchasePlace place(Long id, String name, int sortOrder) {
        return PurchasePlace.builder().id(id).name(name).sortOrder(sortOrder).build();
    }

    private PurchasePlaceRequest request(String name) {
        return PurchasePlaceRequest.builder().name(name).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_emptyList_seedsThreeDefaultsInOrder() {
        given(purchasePlaceRepository.findAllByOrderBySortOrderAscIdAsc()).willReturn(List.of());
        given(purchasePlaceRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));

        List<PurchasePlaceResponse> result = service.list();

        ArgumentCaptor<List<PurchasePlace>> captor = ArgumentCaptor.forClass(List.class);
        verify(purchasePlaceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(PurchasePlace::getName).containsExactly("이마트", "코스트코", "노브랜드");
        assertThat(captor.getValue()).extracting(PurchasePlace::getSortOrder).containsExactly(0, 1, 2);
        assertThat(result).extracting(PurchasePlaceResponse::getName).containsExactly("이마트", "코스트코", "노브랜드");
    }

    @Test
    void list_nonEmptyList_seedsNothingAndCountsActiveProducts() {
        given(purchasePlaceRepository.findAllByOrderBySortOrderAscIdAsc())
                .willReturn(List.of(place(1L, "이마트", 0), place(4L, "동네마트", 3)));
        given(productPurchasePlaceRepository.countActiveProductsByPlaceIdIn(List.of(1L, 4L)))
                .willReturn(List.<Object[]>of(new Object[]{1L, 12L}));

        List<PurchasePlaceResponse> result = service.list();

        verify(purchasePlaceRepository, never()).saveAll(any());
        assertThat(result).extracting(PurchasePlaceResponse::getProductCount).containsExactly(12, 0);
    }

    @Test
    void create_duplicateTrimmedName_throws() {
        given(purchasePlaceRepository.existsByName("이마트")).willReturn(true);

        assertThatThrownBy(() -> service.create(request("  이마트 ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 있는 구매처 이름입니다: 이마트");
        verify(purchasePlaceRepository, never()).save(any());
    }

    @Test
    void create_takesNextSortOrder() {
        given(purchasePlaceRepository.existsByName("동네마트")).willReturn(false);
        given(purchasePlaceRepository.findAllByOrderBySortOrderAscIdAsc())
                .willReturn(List.of(place(1L, "이마트", 0), place(2L, "코스트코", 5)));
        given(purchasePlaceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        PurchasePlaceResponse response = service.create(request("동네마트"));

        assertThat(response.getSortOrder()).isEqualTo(6);
        assertThat(response.getProductCount()).isZero();
    }

    @Test
    void rename_sameNameOnItself_isAllowed() {
        given(purchasePlaceRepository.findScopedById(1L)).willReturn(Optional.of(place(1L, "이마트", 0)));
        given(purchasePlaceRepository.existsByNameAndIdNot("이마트 트레이더스", 1L)).willReturn(false);
        given(purchasePlaceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        PurchasePlaceResponse response = service.rename(1L, request("이마트 트레이더스"));

        assertThat(response.getName()).isEqualTo("이마트 트레이더스");
        assertThat(response.getSortOrder()).isZero();
    }

    @Test
    void delete_usedByActiveProducts_throwsAndDeletesNothing() {
        given(purchasePlaceRepository.findScopedById(1L)).willReturn(Optional.of(place(1L, "이마트", 0)));
        given(productPurchasePlaceRepository.countActiveProductsByPlaceIdIn(List.of(1L)))
                .willReturn(List.<Object[]>of(new Object[]{1L, 3L}));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("3개 물품이 사용 중입니다");
        verify(productPurchasePlaceRepository, never()).deleteByPurchasePlaceId(any());
        verify(purchasePlaceRepository, never()).delete(any());
    }

    @Test
    void delete_unused_removesLeftoverLinksThenPlace() {
        PurchasePlace place = place(4L, "동네마트", 3);
        given(purchasePlaceRepository.findScopedById(4L)).willReturn(Optional.of(place));
        given(productPurchasePlaceRepository.countActiveProductsByPlaceIdIn(List.of(4L))).willReturn(List.of());

        service.delete(4L);

        verify(productPurchasePlaceRepository).deleteByPurchasePlaceId(4L);
        verify(purchasePlaceRepository).delete(place);
    }
}
