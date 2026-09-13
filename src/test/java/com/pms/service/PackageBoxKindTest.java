package com.pms.service;

import com.pms.domain.BoxKind;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PackageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Box kind rules (FEATURE_2609_40 / PLAN D20 · D21).
 *
 * <p>🔴 The regression this file exists for: a recycled box costs 0, so if one can be picked from a
 * selling-price list it eventually becomes a master/option default box and the goods get priced off
 * nothing. Two independent defences are covered here — the list filter and the resolver rejection.</p>
 */
@ExtendWith(MockitoExtension.class)
class PackageBoxKindTest {

    @Mock private PackageRepository packageRepository;
    @Mock private ImageStorageService imageStorageService;
    @Mock private ImageValidator imageValidator;
    @InjectMocks private PackageServiceImpl packageService;

    // Second subject: the resolver owns defence ② and shares no mocks with the service above.
    @Mock private CategoryMappingRepository categoryMappingRepository;
    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private MasterChannelConfigServiceImpl masterChannelConfigService;

    private PackageRequest request(BoxKind kind, String cost, boolean isDefault) {
        return PackageRequest.builder()
                .type("라면상자").cost(new BigDecimal(cost)).effectiveDate(LocalDate.now()).isDefault(isDefault)
                .widthCm(new BigDecimal("22.0")).lengthCm(new BigDecimal("19.0")).heightCm(new BigDecimal("9.0"))
                .boxKind(kind)
                .build();
    }

    private Package box(long id, String type, BoxKind kind, String cost) {
        return Package.builder()
                .id(id).type(type).cost(new BigDecimal(cost)).effectiveDate(LocalDate.now()).isDefault(false)
                .widthCm(new BigDecimal("22.0")).lengthCm(new BigDecimal("19.0")).heightCm(new BigDecimal("9.0"))
                .boxKind(kind)
                .build();
    }

    // ---- cost rule (the only validation that branches on kind) ----

    @Test
    void testRecycledBoxAllowsZeroCost() {
        given(packageRepository.save(any())).willReturn(box(1L, "라면상자", BoxKind.RECYCLED, "0"));

        PackageResponse response = packageService.createPackage(request(BoxKind.RECYCLED, "0", false));

        assertThat(response.getBoxKind()).isEqualTo(BoxKind.RECYCLED);
        ArgumentCaptor<Package> saved = ArgumentCaptor.forClass(Package.class);
        verify(packageRepository).save(saved.capture());
        assertThat(saved.getValue().getBoxKind()).isEqualTo(BoxKind.RECYCLED);
        assertThat(saved.getValue().getCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testPurchasedBoxRejectsZeroCost() {
        assertThatThrownBy(() -> packageService.createPackage(request(BoxKind.PURCHASED, "0", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상자비");

        verify(packageRepository, never()).save(any());
    }

    @Test
    void testRecycledBoxCannotBeDefault() {
        // The default box is what the selling-price calculation falls back to (D21).
        assertThatThrownBy(() -> packageService.createPackage(request(BoxKind.RECYCLED, "0", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기본 상자");

        verify(packageRepository, never()).save(any());
    }

    // ---- defence ①: the list API filter ----

    @Test
    void testPricingListExcludesRecycled() {
        given(packageRepository.findByBoxKind(BoxKind.PURCHASED))
                .willReturn(List.of(box(1L, "소박스", BoxKind.PURCHASED, "300")));

        List<PackageResponse> responses = packageService.getPackages(BoxKind.PURCHASED);

        assertThat(responses).extracting(PackageResponse::getBoxKind).containsOnly(BoxKind.PURCHASED);
        assertThat(responses).extracting(PackageResponse::getId).containsExactly(1L);
        verify(packageRepository, never()).findAll();
    }

    @Test
    void testPackageListDefaultsToAllKinds() {
        given(packageRepository.findAll()).willReturn(List.of(
                box(1L, "소박스", BoxKind.PURCHASED, "300"),
                box(2L, "라면상자", BoxKind.RECYCLED, "0")));

        List<PackageResponse> responses = packageService.getPackages(null);

        // Hiding recycled boxes by default would blind the packing screen — the one place they belong.
        assertThat(responses).extracting(PackageResponse::getBoxKind)
                .containsExactly(BoxKind.PURCHASED, BoxKind.RECYCLED);
        verify(packageRepository, never()).findByBoxKind(any());
    }

    // ---- defence ②: the resolver rejection ----

    @Test
    void testResolvePackageRejectsRecycled() {
        MasterProductOption option = MasterProductOption.builder()
                .package_(box(2L, "라면상자", BoxKind.RECYCLED, "0"))
                .build();
        ProductListing cell = ProductListing.builder()
                .id(1L).platform(Platform.COUPANG).masterProduct(MasterProduct.builder().id(9L).build())
                .build();

        // Wrong data, not a runtime hiccup: no fallback box, make the caller fix the assignment.
        assertThatThrownBy(() -> masterChannelConfigService.resolvePackage(cell, option))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("재활용 상자");
    }
}
