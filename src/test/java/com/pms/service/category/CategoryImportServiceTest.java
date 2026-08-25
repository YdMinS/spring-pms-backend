package com.pms.service.category;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.PlatformCategory;
import com.pms.dto.response.CategoryImportResult;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PlatformCategoryRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CategoryImportServiceImpl} against a real DB (FEATURE_2608_06 / 53) — multi-repo tree upsert needs
 * real persistence, not Mockito. Behavior-focused: initial seed counts, idempotent re-import (fee update +
 * mirror preserved), rename-safe leaf reuse, and single-new-leaf incremental import.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class CategoryImportServiceTest {

    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;

    private CategoryImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CategoryImportServiceImpl(new CoupangCategoryXlsxParser(),
                platformCategoryRepository, categoryRepository, categoryMappingRepository);
    }

    // Two leaves sharing intermediates 식품>가공/즉석식품>라면.
    private InputStream baseFixture(String fee) throws IOException {
        return xlsx(
                new String[]{"[58646] 식품>가공/즉석식품>라면>봉지라면", fee},
                new String[]{"[58647] 식품>가공/즉석식품>라면>컵라면", fee});
    }

    @Test
    void initialImport_buildsPlatformTree_oclyxMirror_andMappings() throws IOException {
        CategoryImportResult r = service.importCoupang(baseFixture("10.6"));

        assertThat(r.getLeavesProcessed()).isEqualTo(2);
        assertThat(r.getPlatformNodesCreated()).isEqualTo(5); // 3 intermediates + 2 leaves
        assertThat(r.getPlatformNodesUpdated()).isZero();
        assertThat(r.getOclyxNodesCreated()).isEqualTo(5);
        assertThat(r.getMappingsCreated()).isEqualTo(2);
        assertThat(r.getSkipped()).isZero();

        // Leaf carries code + commission (fraction); intermediate carries neither.
        PlatformCategory leaf = platformCategoryRepository.findByPlatformAndCode("COUPANG", "58646").orElseThrow();
        assertThat(leaf.getCode()).isEqualTo("58646");
        assertThat(leaf.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.106"));
        PlatformCategory root = platformCategoryRepository.findByParentIsNullAndPlatform("COUPANG").get(0);
        assertThat(root.getName()).isEqualTo("식품");
        assertThat(root.getCode()).isNull();
        assertThat(root.getCommissionRate()).isNull();

        // Mirror mapping links the oclyx leaf to the PlatformCategory leaf.
        CategoryMapping mapping = categoryMappingRepository.findByPlatformCategoryId(leaf.getId()).orElseThrow();
        assertThat(mapping.getCategory().getName()).isEqualTo("봉지라면");
        assertThat(mapping.getPlatformCategoryId()).isEqualTo("58646"); // legacy String col = mall code
    }

    @Test
    void reImport_withChangedFee_updatesPlatform_preservesMirror_noDuplicates() throws IOException {
        service.importCoupang(baseFixture("10.6"));
        long oclyxCountAfterFirst = categoryRepository.count();
        long mappingCountAfterFirst = categoryMappingRepository.count();

        CategoryImportResult r = service.importCoupang(baseFixture("12.0"));

        assertThat(r.getPlatformNodesCreated()).isZero();
        assertThat(r.getPlatformNodesUpdated()).isEqualTo(2); // both leaves refreshed
        assertThat(r.getOclyxNodesCreated()).isZero();
        assertThat(r.getMappingsCreated()).isZero();
        assertThat(r.getSkipped()).isEqualTo(2);

        assertThat(categoryRepository.count()).isEqualTo(oclyxCountAfterFirst);       // mirror untouched
        assertThat(categoryMappingRepository.count()).isEqualTo(mappingCountAfterFirst);
        assertThat(platformCategoryRepository.findByPlatformAndCode("COUPANG", "58646")
                .orElseThrow().getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.12"));
    }

    @Test
    void reImport_afterLeafRename_reusesMirrorByFk_preservesRenamedName() throws IOException {
        service.importCoupang(baseFixture("10.6"));
        PlatformCategory leaf = platformCategoryRepository.findByPlatformAndCode("COUPANG", "58646").orElseThrow();
        Category mirror = categoryMappingRepository.findByPlatformCategoryId(leaf.getId()).orElseThrow().getCategory();
        categoryRepository.save(mirror.toBuilder().name("사용자수정라면").build()); // user renamed the mirror leaf
        long oclyxCount = categoryRepository.count();
        long mappingCount = categoryMappingRepository.count();

        CategoryImportResult r = service.importCoupang(baseFixture("10.6"));

        assertThat(r.getOclyxNodesCreated()).isZero();  // FK reverse lookup finds the renamed mirror
        assertThat(r.getMappingsCreated()).isZero();
        assertThat(r.getSkipped()).isEqualTo(2);
        assertThat(categoryRepository.count()).isEqualTo(oclyxCount);   // no duplicate mirror
        assertThat(categoryMappingRepository.count()).isEqualTo(mappingCount);
        assertThat(categoryRepository.findById(mirror.getId()).orElseThrow().getName())
                .isEqualTo("사용자수정라면");                                     // rename preserved
    }

    @Test
    void reImport_withNewLeaf_addsOnlyThatMirrorAndMapping() throws IOException {
        service.importCoupang(baseFixture("10.6"));

        CategoryImportResult r = service.importCoupang(xlsx(
                new String[]{"[58646] 식품>가공/즉석식품>라면>봉지라면", "10.6"},
                new String[]{"[58647] 식품>가공/즉석식품>라면>컵라면", "10.6"},
                new String[]{"[58648] 식품>가공/즉석식품>라면>건면", "10.6"})); // new leaf

        assertThat(r.getPlatformNodesCreated()).isEqualTo(1); // only 건면 leaf (intermediates reused)
        assertThat(r.getOclyxNodesCreated()).isEqualTo(1);
        assertThat(r.getMappingsCreated()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(2);
        assertThat(platformCategoryRepository.findByPlatformAndCode("COUPANG", "58648")).isPresent();
    }

    /** In-memory data-sheet xlsx: header rows 1-3 dummy, leaf rows from row 5. */
    private InputStream xlsx(String[]... dataRows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("data");
            for (int i = 0; i < 3; i++) {
                sheet.createRow(i).createCell(0).setCellValue("헤더" + i);
            }
            sheet.createRow(3);
            int rowIdx = 4;
            for (String[] cells : dataRows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(cells[0]);
                if (cells.length > 1 && cells[1] != null) {
                    row.createCell(1).setCellValue(Double.parseDouble(cells[1]));
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
