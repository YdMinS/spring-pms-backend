package com.pms.service.category;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.ElevenstCategoryImportResult;
import com.pms.dto.response.ElevenstFeeImportResult;
import com.pms.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link ElevenstCategoryImportServiceImpl} against a real DB (FEATURE_2610_10). Tree: first import, re-import
 * (rename / move / gone node kept / commission kept). Fees: stored values (D25 · D27 ⑥~⑧ · D28 · D30), the
 * listed rows (D27 ⑨ · D29), two rows on one category, a row on a node a re-import left empty, the borrower's
 * own row, re-import overwrite (D31), and the tree-first guard.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ElevenstCategoryImportServiceTest {

    @Autowired private PlatformCategoryRepository platformCategoryRepository;

    private final ElevenstCategoryClient client = mock(ElevenstCategoryClient.class);
    private ElevenstCategoryImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ElevenstCategoryImportServiceImpl(client, new ElevenstCategoryXmlParser(),
                new ElevenstFeeTableParser(), platformCategoryRepository, new BigDecimal("0.1"));
        given(client.fetchCategoryXml()).willReturn(ElevenstFixtures.treeXml());
    }

    private PlatformCategory leaf(String code) {
        return platformCategoryRepository.findByPlatformAndCode(Platform.ELEVENST, code).orElseThrow();
    }

    private void setRate(String code, String rate) {
        platformCategoryRepository.save(leaf(code).toBuilder().commissionRate(new BigDecimal(rate)).build());
    }

    // ---- tree (D21 ① ② · D24) ----

    @Test
    void importTree_first_createsEveryNode_onlyLeavesKeepCode() {
        ElevenstCategoryImportResult r = service.importTree();

        assertThat(r.getPlatformNodesCreated()).isEqualTo(24);
        assertThat(r.getPlatformNodesUpdated()).isZero();
        assertThat(r.getNodesProcessed()).isEqualTo(24);
        assertThat(platformCategoryRepository.findByPlatform(Platform.ELEVENST)).hasSize(24);
        assertThat(platformCategoryRepository.findByParentIsNullAndPlatform(Platform.ELEVENST)).hasSize(9)
                .allSatisfy(root -> assertThat(root.getCode()).isNull());
        PlatformCategory tablet = leaf("121");
        assertThat(tablet.getName()).isEqualTo("안드로이드태블릿");
        assertThat(tablet.getParent().getName()).isEqualTo("태블릿");
        assertThat(tablet.getParent().getCode()).isNull();
        assertThat(tablet.getCommissionRate()).isNull();
    }

    @Test
    void importTree_again_refreshesNameAndParent_keepsGoneNodeAndCommission() {
        service.importTree();
        setRate("21", "0.0636");
        given(client.fetchCategoryXml()).willReturn(ElevenstFixtures.treeXml(ElevenstFixtures.TREE_BODY
                .replace(ElevenstFixtures.cat(2, "스마트워치", "11", "10", true),
                        ElevenstFixtures.cat(2, "스마트밴드", "11", "10", true))
                .replace(ElevenstFixtures.cat(3, "안드로이드태블릿", "121", "12", true),
                        ElevenstFixtures.cat(2, "안드로이드태블릿", "121", "10", true))
                .replace(ElevenstFixtures.cat(2, "식기/홈세트", "22", "20", true), "")));

        ElevenstCategoryImportResult r = service.importTree();

        assertThat(r.getPlatformNodesCreated()).isZero();
        assertThat(r.getPlatformNodesUpdated()).isEqualTo(12);
        assertThat(r.getNodesProcessed()).isEqualTo(23);
        assertThat(leaf("11").getName()).isEqualTo("스마트밴드");
        assertThat(leaf("121").getParent().getName()).isEqualTo("스마트기기");
        assertThat(leaf("22").getName()).isEqualTo("식기/홈세트");                      // gone from 11st, kept
        assertThat(leaf("21").getCommissionRate()).isEqualByComparingTo("0.0636");    // tree import keeps fees
        assertThat(platformCategoryRepository.findByPlatform(Platform.ELEVENST)).hasSize(24);
    }

    // ---- fees (D25 ~ D31) ----

    @Test
    void importFees_writesStoredRatesByRowExceptionFixAndBorrow() {
        service.importTree();

        ElevenstFeeImportResult r = service.importFees(ElevenstFixtures.feeHtml());

        assertThat(r.getLeavesUpdated()).isEqualTo(12);
        assertThat(leaf("11").getCommissionRate()).isEqualByComparingTo("0.0818");   // domestic 「스마트기기」 9%
        assertThat(leaf("121").getCommissionRate()).isEqualByComparingTo("0.0818");
        assertThat(leaf("411").getCommissionRate()).isEqualByComparingTo("0.1182");  // 「해외직구」 > 「스마트기기」 13%
        assertThat(leaf("21").getCommissionRate()).isEqualByComparingTo("0.0636");   // exception 「프라이팬」 7%
        assertThat(leaf("22").getCommissionRate()).isEqualByComparingTo("0.1182");
        assertThat(leaf("31").getCommissionRate()).isEqualByComparingTo("0.1091");   // 「금연용품」 12%
        assertThat(leaf("32").getCommissionRate()).isEqualByComparingTo("0.1000");   // 「수지침」 11%
        assertThat(leaf("33").getCommissionRate()).isEqualByComparingTo("0.1182");
        assertThat(leaf("51").getCommissionRate()).isEqualByComparingTo("0.1182");
        assertThat(leaf("61").getCommissionRate()).isEqualByComparingTo("0.1182");   // 「구강/면도」 ← 「욕실용품」 (D28)
        assertThat(leaf("81").getCommissionRate()).isEqualByComparingTo("0.1182");   // 「캐쥬얼」 → 「캐주얼」 (D30)
        assertThat(leaf("91").getCommissionRate()).isEqualByComparingTo("0.0091");   // 1%
        assertThat(leaf("71").getCommissionRate()).isNull();                          // 「비즈11번가」 (D28)
    }

    @Test
    void importFees_listsUnplacedRowsExceptionsAndBorrow() {
        service.importTree();

        ElevenstFeeImportResult r = service.importFees(ElevenstFixtures.feeHtml());

        assertThat(r.getUnmatchedCategories()).containsExactly(
                new ElevenstFeeImportResult.Item("생활/건강", "청소용품", null, "13%"),
                new ElevenstFeeImportResult.Item("기타", "주방잡화", null, "정액"));
        assertThat(r.getUnmatchedExceptions()).containsExactly(
                new ElevenstFeeImportResult.Item("생활/건강", "주방용품", "기타 주방용품", "7%"),
                new ElevenstFeeImportResult.Item("생활/건강", "건강관리용품", "성인용품", "12%"));
        assertThat(r.getBorrowed()).containsExactly(
                new ElevenstFeeImportResult.Borrowed("구강/면도", "욕실용품", "13%"));
    }

    @Test
    void importFees_twoRowsOnOneCategory_savesNeither_andNoLenderIsListed() {
        service.importTree();
        String rows = """
                <tr><td>생활/건강</td><td>주방용품</td><td>13%</td><td></td></tr>
                <tr><td>생활/건강</td><td>주방 용품</td><td>12%</td><td></td></tr>
                """;

        ElevenstFeeImportResult r = service.importFees(ElevenstFixtures.feeHtml(rows));

        assertThat(r.getLeavesUpdated()).isZero();
        assertThat(r.getUnmatchedCategories()).containsExactly(
                new ElevenstFeeImportResult.Item("생활/건강", "주방용품", null, "13%"),
                new ElevenstFeeImportResult.Item("생활/건강", "주방 용품", null, "12%"),
                new ElevenstFeeImportResult.Item(null, "구강/면도", null, null));
        assertThat(leaf("21").getCommissionRate()).isNull();
    }

    @Test
    void importFees_borrowerHasOwnRow_usesItAndBorrowsNothing() {
        service.importTree();
        String rows = """
                <tr><td>생활/건강</td><td>구강/면도</td><td>12%</td><td></td></tr>
                <tr><td>생활/건강</td><td>욕실용품</td><td>13%</td><td></td></tr>
                """;

        ElevenstFeeImportResult r = service.importFees(ElevenstFixtures.feeHtml(rows));

        assertThat(leaf("61").getCommissionRate()).isEqualByComparingTo("0.1091");
        assertThat(r.getBorrowed()).isEmpty();
    }

    @Test
    void importFees_again_overwritesTableLeaves_keepsLeavesTheTableMisses() {
        service.importTree();
        setRate("21", "0.2000");
        setRate("71", "0.0500");

        service.importFees(ElevenstFixtures.feeHtml());

        assertThat(leaf("21").getCommissionRate()).isEqualByComparingTo("0.0636");
        assertThat(leaf("71").getCommissionRate()).isEqualByComparingTo("0.0500");
    }

    @Test
    void importFees_rowOnNodeLeftEmptyByReimport_isListed() {
        service.importTree();
        given(client.fetchCategoryXml()).willReturn(ElevenstFixtures.treeXml(ElevenstFixtures.TREE_BODY
                .replace(ElevenstFixtures.cat(1, "욕실용품", "50", "0", false),
                        ElevenstFixtures.cat(1, "욕실/샤워용품", "50", "0", false))));
        service.importTree();

        ElevenstFeeImportResult r = service.importFees(ElevenstFixtures.feeHtml());

        assertThat(r.getUnmatchedCategories()).contains(
                new ElevenstFeeImportResult.Item("생활/건강", "욕실용품", null, "13%"));
        assertThat(leaf("51").getCommissionRate()).isNull();
    }

    @Test
    void importFees_beforeTreeImport_throws400() {
        assertThatThrownBy(() -> service.importFees(ElevenstFixtures.feeHtml()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 카테고리 목록을 먼저 들여와야 합니다.");
    }

    @Test
    void toStoredRate_dividesOutVatAndRoundsOnceToFourPlaces() {
        int[] percents = {1, 2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 15};
        String[] stored = {"0.0091", "0.0182", "0.0364", "0.0455", "0.0636", "0.0727", "0.0818", "0.0909",
                "0.1000", "0.1091", "0.1182", "0.1364"};
        for (int i = 0; i < percents.length; i++) {
            assertThat(service.toStoredRate(percents[i])).isEqualTo(new BigDecimal(stored[i]));
        }
    }
}
