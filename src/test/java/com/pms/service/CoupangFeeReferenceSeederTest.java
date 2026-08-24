package com.pms.service;

import com.pms.domain.CoupangFeeReference;
import com.pms.repository.CoupangFeeReferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Coupang fee reference seeder (FEATURE_2608_06 / 46): parses the real classpath CSV (row count) and is
 * idempotent (skips when the table is already populated).
 */
@ExtendWith(MockitoExtension.class)
class CoupangFeeReferenceSeederTest {

    @Mock private CoupangFeeReferenceRepository repository;
    @InjectMocks private CoupangFeeReferenceSeeder seeder;

    @Test
    void emptyTable_seedsAllCsvRows() throws Exception {
        given(repository.count()).willReturn(0L);

        seeder.run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoupangFeeReference>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<CoupangFeeReference> rows = captor.getValue();
        assertThat(rows).hasSize(125);                    // 125 data rows in coupang-fee-table.csv (header skipped)

        // spot-check a major-default row (blank jung/so) and a sub row (with a leaf '/').
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDae()).isEqualTo("가전디지털");
            assertThat(r.getJung()).isEmpty();
            assertThat(r.getSo()).isEmpty();
            assertThat(r.getRate()).isEqualByComparingTo("0.078");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getDae()).isEqualTo("가전디지털");
            assertThat(r.getJung()).isEqualTo("카메라/카메라용품");
            assertThat(r.getSo()).isEqualTo("DSLR/SLR카메라");
            assertThat(r.getRate()).isEqualByComparingTo("0.058");
        });
    }

    @Test
    void alreadyPopulated_isIdempotentSkip() throws Exception {
        given(repository.count()).willReturn(125L);

        seeder.run(null);

        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
