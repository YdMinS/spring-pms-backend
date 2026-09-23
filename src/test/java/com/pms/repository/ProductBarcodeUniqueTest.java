package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code uq_products_tenant_barcode} key itself (changeset 098).
 *
 * <p>The service guard is unit-tested with a mocked repository, which proves the 409 but not that the
 * database would have stopped a duplicate anyway — and the guard is not the only way a row reaches the
 * table (migrations, backfills, a future import path). Only a real round-trip shows the key exists.</p>
 *
 * <p>⚠️ The schema here is built by Hibernate ({@code ddl-auto: create-drop}), not by Liquibase, so what is
 * actually under test is the {@code @Table(uniqueConstraints = ...)} declaration on {@link Product}.
 * Changeset 098 has to state the same key for dev/prod, where Liquibase owns the schema.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ProductBarcodeUniqueTest {

    @Autowired private ProductRepository repository;
    @Autowired private TestEntityManager em;

    @Test
    void secondProductWithTheSameBarcodeIsRejected() {
        repository.save(Product.builder().productName("아몬드 초코볼").barcodeId("2087686005954").active(true).build());
        em.flush();

        Product duplicate = Product.builder()
                .productName("어쏘티드 초코볼").barcodeId("2087686005954").active(true).build();

        // IDENTITY ids make the INSERT happen inside save(), so the key fires there rather than at flush
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 🔴 The reason the column stays nullable: ~1,200 products carry no barcode at all, and a unique key
     * that counted NULLs as equal would let exactly one of them exist.
     */
    @Test
    void productsWithoutABarcodeAreUnlimited() {
        repository.save(Product.builder().productName("바코드 없음 1").active(true).build());
        repository.save(Product.builder().productName("바코드 없음 2").active(true).build());
        repository.save(Product.builder().productName("바코드 없음 3").active(true).build());
        em.flush();

        assertThat(repository.findAll()).hasSize(3);
    }
}
