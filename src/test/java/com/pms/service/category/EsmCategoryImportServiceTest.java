package com.pms.service.category;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.EsmCategoryImportResult;
import com.pms.dto.response.EsmFeeImportResult;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.service.esm.EsmCategories;
import com.pms.service.esm.EsmKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_26 / D12 · D14 · D62 · D63 · D64 · D67: ESM tree upsert and the fee table import. */
@ExtendWith(MockitoExtension.class)
class EsmCategoryImportServiceTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private EsmCategories esmCategories;
    @Mock private EsmFeeTableParser feeParser;

    private EsmCategoryImportServiceImpl service;
    private final MarketplaceAccount account =
            MarketplaceAccountFixture.esmStubBuilder("m", "s", "g", "a").id(3L).build();
    private final EsmKey key = new EsmKey("m", "s", "g", "a");

    @BeforeEach
    void setUp() {
        service = new EsmCategoryImportServiceImpl(marketplaceAccountRepository, platformCategoryRepository,
                esmCategories, feeParser, new BigDecimal("0.1"));
        given(marketplaceAccountRepository.findById(3L)).willReturn(Optional.of(account));
    }

    @Test
    void importTree_leavesKeepTheirCode_intermediatesHaveNone() {
        given(platformCategoryRepository.findByPlatform(Platform.ESM)).willReturn(List.of());
        given(platformCategoryRepository.save(any())).willAnswer(inv -> {
            PlatformCategory pc = inv.getArgument(0);
            return pc.toBuilder().id(pc.getCode() == null ? 100L : 200L).build();
        });
        given(esmCategories.sdChildren(key, "0"))
                .willReturn(List.of(new EsmCategories.SdCategory("00320000000000000000", "생활서비스", false)));
        given(esmCategories.sdChildren(key, "00320000000000000000"))
                .willReturn(List.of(new EsmCategories.SdCategory("00320005000000000000", "기타생활서비스", true)));

        EsmCategoryImportResult result = service.importTree(3L);

        ArgumentCaptor<PlatformCategory> saved = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getCode()).isNull();
        assertThat(saved.getAllValues().get(1).getCode()).isEqualTo("00320005000000000000");
        assertThat(saved.getAllValues().get(1).getParent().getId()).isEqualTo(100L);
        assertThat(result.getPlatformNodesCreated()).isEqualTo(2);
        assertThat(result.getNodesProcessed()).isEqualTo(2);
    }

    @Test
    void importFees_middleRowWins_thenTheWholeRow_storedWithoutVat_inBothColumns() {
        PlatformCategory mask = PlatformCategory.builder().id(1L).platform(Platform.ESM).code("E-MASK").name("마스크")
                .build();
        PlatformCategory other = PlatformCategory.builder().id(2L).platform(Platform.ESM).code("E-OTHER")
                .name("기타").build();
        PlatformCategory lost = PlatformCategory.builder().id(3L).platform(Platform.ESM).code("E-LOST").name("미확인")
                .build();
        given(platformCategoryRepository.findByPlatform(Platform.ESM)).willReturn(List.of(mask, other, lost));
        given(feeParser.parse(any())).willReturn(List.of(
                new EsmFeeRow("건강/의료용품", "전체", new BigDecimal("0.09"), "9%"),
                new EsmFeeRow("건강/의료용품", "마스크", new BigDecimal("0.11"), "11%"),
                new EsmFeeRow("상품권", "전체", new BigDecimal("0.01"), "1%")));
        given(esmCategories.gmarketCategory(key, null)).willReturn(new EsmCategories.SiteCategory(null, null, false,
                List.of(new EsmCategories.SiteCategory("100", "건강/의료용품", false, List.of()))));
        given(esmCategories.gmarketCategory(key, "100")).willReturn(new EsmCategories.SiteCategory("100",
                "건강/의료용품", false, List.of(
                        new EsmCategories.SiteCategory("201", "마스크", true, List.of()),
                        new EsmCategories.SiteCategory("202", "체온계", true, List.of()))));
        given(esmCategories.matchedLeaves(key, "E-MASK")).willReturn(new EsmCategories.SiteLeaves("201", "A1"));
        given(esmCategories.matchedLeaves(key, "E-OTHER")).willReturn(new EsmCategories.SiteLeaves("202", "A2"));
        given(esmCategories.matchedLeaves(key, "E-LOST")).willReturn(new EsmCategories.SiteLeaves(null, null));

        EsmFeeImportResult result = service.importFees(3L, new byte[0]);

        ArgumentCaptor<PlatformCategory> saved = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getCommissionRate()).isEqualByComparingTo("0.1000");
        assertThat(saved.getAllValues().get(0).getAuctionCommissionRate()).isEqualByComparingTo("0.1000");
        assertThat(saved.getAllValues().get(1).getCommissionRate()).isEqualByComparingTo("0.0818");
        assertThat(result.getLeavesUpdated()).isEqualTo(2);
        assertThat(result.getEmptyLeaves()).extracting(EsmFeeImportResult.Leaf::code).containsExactly("E-LOST");
        assertThat(result.getUnusedRows()).extracting(EsmFeeImportResult.Row::major).containsExactly("상품권");
    }

    @Test
    void importFees_beforeTheTree_rejected() {
        given(platformCategoryRepository.findByPlatform(Platform.ESM)).willReturn(List.of());
        given(feeParser.parse(any())).willReturn(List.of(new EsmFeeRow("a", "전체", BigDecimal.ONE, "1")));

        assertThatThrownBy(() -> service.importFees(3L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("G마켓 · 옥션 카테고리 목록을 먼저 들여와야 합니다.");
    }
}
