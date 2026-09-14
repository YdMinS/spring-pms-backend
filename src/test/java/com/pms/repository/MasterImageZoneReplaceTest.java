package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.service.ImageStorageService;
import com.pms.service.ImageValidator;
import com.pms.service.MasterProductImageServiceImpl;
import com.pms.service.ProductImageUrlResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Zone/cover replacement against a real DB — the mock-based {@code MasterProductImageServiceTest} cannot
 * see this: replacement is delete-then-insert, and only a real transaction reveals that the derived delete
 * merely queues its removals while the inserts below fire immediately (IDENTITY ids). Without a flush in
 * between, re-inserting a row that is still physically present violates {@code uq_miza_image_zone} (024) and
 * the call 500s — a zone would accept its first image but never a second one.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class MasterImageZoneReplaceTest {

    private static final String ZONE = "product_photos";

    @Autowired private MasterProductImageRepository imageRepository;
    @Autowired private MasterImageZoneAssignmentRepository assignmentRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private ProductImageRepository productImageRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private TestEntityManager em;

    private MasterProductImageServiceImpl service() {
        ProductImageUrlResolver resolver = Mockito.mock(ProductImageUrlResolver.class);
        Mockito.lenient().when(resolver.resolve(Mockito.any())).thenReturn("https://cdn/x.jpg");
        return new MasterProductImageServiceImpl(
                imageRepository, assignmentRepository, masterProductRepository, productImageRepository,
                productRepository, resolver,
                Mockito.mock(ImageStorageService.class), Mockito.mock(ImageValidator.class));
    }

    private MasterProduct persistMaster() {
        MasterProduct master = em.persist(MasterProduct.builder().name("마스터").active(true).build());
        em.flush();
        return master;
    }

    private Long persistPoolImage(MasterProduct master, int sortOrder) {
        MasterProductImage image = em.persist(MasterProductImage.builder()
                .masterProduct(master).imageUrl("https://cdn/" + sortOrder + ".jpg").sortOrder(sortOrder).build());
        em.flush();
        return image.getId();
    }

    private List<MasterImageZoneAssignment> zoneRows(Long masterId, String zoneId) {
        return assignmentRepository
                .findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(masterId, zoneId);
    }

    /** The reported bug: the second photo re-sends the first one, whose row is still in the zone. */
    @Test
    void setZoneImages_addingASecondImageKeepsBoth() {
        MasterProduct master = persistMaster();
        Long first = persistPoolImage(master, 0);
        Long second = persistPoolImage(master, 1);
        MasterProductImageServiceImpl service = service();

        service.setZoneImages(master.getId(), ZONE, List.of(first));
        em.flush();
        em.clear();

        assertThatCode(() -> service.setZoneImages(master.getId(), ZONE, List.of(first, second)))
                .doesNotThrowAnyException();
        em.flush();
        em.clear();

        assertThat(zoneRows(master.getId(), ZONE))
                .extracting(a -> a.getImage().getId())
                .containsExactly(first, second);
    }

    /** Reordering re-inserts both rows — every id in the request is already mapped to this zone. */
    @Test
    void setZoneImages_reorderingKeepsBothInTheNewOrder() {
        MasterProduct master = persistMaster();
        Long first = persistPoolImage(master, 0);
        Long second = persistPoolImage(master, 1);
        MasterProductImageServiceImpl service = service();

        service.setZoneImages(master.getId(), ZONE, List.of(first, second));
        em.flush();
        em.clear();

        service.setZoneImages(master.getId(), ZONE, List.of(second, first));
        em.flush();
        em.clear();

        assertThat(zoneRows(master.getId(), ZONE))
                .extracting(a -> a.getImage().getId())
                .containsExactly(second, first);
    }

    /** Same delete-then-insert contract on the cover: re-picking the current cover must stay a no-op. */
    @Test
    void setSourceImage_rePickingTheCurrentCoverKeepsTheSingleRow() {
        MasterProduct master = persistMaster();
        Long cover = persistPoolImage(master, 0);
        MasterProductImageServiceImpl service = service();

        service.setSourceImage(master.getId(), cover);
        em.flush();
        em.clear();

        assertThatCode(() -> service.setSourceImage(master.getId(), cover)).doesNotThrowAnyException();
        em.flush();
        em.clear();

        assertThat(zoneRows(master.getId(), MasterImageZoneAssignment.SOURCE_ZONE))
                .extracting(a -> a.getImage().getId())
                .containsExactly(cover);
    }
}
