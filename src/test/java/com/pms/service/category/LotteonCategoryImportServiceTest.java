package com.pms.service.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.LotteonCategoryImportResult;
import com.pms.dto.response.LotteonFeeImportResult;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_24 / D9 · D10 · D46 · D48: LotteON standard category tree and commission CSV. */
@ExtendWith(MockitoExtension.class)
class LotteonCategoryImportServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private LotteonApiClient client;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;

    private LotteonCategoryImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LotteonCategoryImportServiceImpl(client, new LotteonFeeCsvParser(), platformCategoryRepository,
                marketplaceAccountRepository, new BigDecimal("0.1"));
    }

    private static String row(String id, String parent, String name, int depth, String leaf, String use) {
        return "{\"data\":{\"std_cat_id\":\"" + id + "\",\"upr_std_cat_id\":\"" + parent + "\",\"std_cat_nm\":\""
                + name + "\",\"depth_no\":\"" + depth + "\",\"leaf_yn\":\"" + leaf + "\",\"use_yn\":\"" + use + "\"}}";
    }

    @Test
    void importTree_readsEveryPage_skipsUnusedAndOrphans_leavesKeepCodesAndNoCommission() throws Exception {
        MarketplaceAccount account = MarketplaceAccountFixture.lotteonStubBuilder("KEY", "SR", "LO1").id(5L).build();
        given(marketplaceAccountRepository.findById(5L)).willReturn(Optional.of(account));
        StringBuilder full = new StringBuilder("{\"itemList\":[");
        for (int i = 0; i < 100; i++) {
            full.append(i == 0 ? "" : ",").append(row("BC" + i, "BC0", "중간" + i, i == 0 ? 1 : 2, "N", "Y"));
        }
        full.append("]}");
        String last = "{\"itemList\":[" + row("BC500", "BC1", "말단", 3, "Y", "Y") + ","
                + row("BC600", "BC1", "안씀", 3, "Y", "N") + "," + row("BC700", "BCX", "고아", 3, "Y", "Y") + "]}";
        given(client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&skip=0&limit=100", "KEY"))
                .willReturn(JSON.readTree(full.toString()));
        given(client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&skip=100&limit=100", "KEY"))
                .willReturn(JSON.readTree(last));
        given(platformCategoryRepository.findByPlatform(Platform.LOTTEON)).willReturn(List.of());
        AtomicLong ids = new AtomicLong(1);
        given(platformCategoryRepository.save(any())).willAnswer(inv ->
                ((PlatformCategory) inv.getArgument(0)).toBuilder().id(ids.getAndIncrement()).build());

        LotteonCategoryImportResult result = service.importTree(5L);

        assertThat(result.getNodesProcessed()).isEqualTo(102);
        assertThat(result.getNodesSkipped()).isEqualTo(1);
        assertThat(result.getPlatformNodesCreated()).isEqualTo(101);
        ArgumentCaptor<PlatformCategory> saved = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository, times(101)).save(saved.capture());
        PlatformCategory leaf = saved.getAllValues().stream().filter(pc -> "말단".equals(pc.getName()))
                .findFirst().orElseThrow();
        assertThat(leaf.getCode()).isEqualTo("BC500");
        assertThat(leaf.getCommissionRate()).isNull();
        assertThat(leaf.getParent().getName()).isEqualTo("중간1");
        assertThat(saved.getAllValues()).filteredOn(pc -> "중간1".equals(pc.getName()))
                .extracting(PlatformCategory::getCode).containsOnlyNulls();
    }

    private PlatformCategory node(long id, String name, String code, PlatformCategory parent) {
        return PlatformCategory.builder().id(id).platform(Platform.LOTTEON).name(name).code(code).parent(parent)
                .build();
    }

    @Test
    void importFees_ownLineElseNearestUpper_storesVatExcluded_listsUnmatched() {
        PlatformCategory top = node(1L, "e쿠폰/티켓", null, null);
        PlatformCategory mid = node(2L, "모바일금액권", null, top);
        PlatformCategory leafWithLine = node(3L, "백화점상품권", "BC3", mid);
        PlatformCategory leafFromTop = node(4L, "기타", "BC4", node(5L, "공연", null, top));
        PlatformCategory other = node(6L, "생활", null, null);
        PlatformCategory leafWithout = node(7L, "세제", "BC7", other);
        given(platformCategoryRepository.findByPlatform(Platform.LOTTEON)).willReturn(List.of(top, mid, leafWithLine,
                leafFromTop, node(5L, "공연", null, top), other, leafWithout));
        String csv = "﻿depth,category_path,commission_pct_vat_incl\n"
                + "1,\"e쿠폰/티켓\",7.00\n"
                + "3,\"e쿠폰/티켓 > 모바일금액권 > 백화점상품권\",3.00\n"
                + "2,\"없는 > 경로\",9.00\n";

        LotteonFeeImportResult result = service.importFees(csv.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<PlatformCategory> saved = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).filteredOn(pc -> "BC3".equals(pc.getCode()))
                .extracting(PlatformCategory::getCommissionRate).containsExactly(new BigDecimal("0.0273"));
        assertThat(saved.getAllValues()).filteredOn(pc -> "BC4".equals(pc.getCode()))
                .extracting(PlatformCategory::getCommissionRate).containsExactly(new BigDecimal("0.0636"));
        assertThat(result.getLeavesUpdated()).isEqualTo(2);
        assertThat(result.getLeavesWithoutFee()).isEqualTo(1);
        assertThat(result.getUnmatchedRows()).containsExactly(new LotteonFeeImportResult.Item("없는 > 경로", "9.00%"));
    }

    @Test
    void importFees_beforeTheTree_isRefused() {
        given(platformCategoryRepository.findByPlatform(Platform.LOTTEON)).willReturn(List.of());

        assertThatThrownBy(() -> service.importFees(
                "depth,category_path,commission_pct_vat_incl\n1,\"가\",1.00\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("롯데ON 카테고리 목록을 먼저 들여와야 합니다.");
        verify(platformCategoryRepository, never()).save(any());
    }

    @Test
    void importFees_wrongHeader_isRefused() {
        assertThatThrownBy(() -> service.importFees("a,b,c\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("롯데ON 수수료 파일의 머리줄이 다릅니다");
    }
}
