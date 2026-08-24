package com.pms.service;

import com.pms.domain.CoupangFeeReference;
import com.pms.repository.CoupangFeeReferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Hierarchical fallback resolver (FEATURE_2608_06 / 46): sub &gt; middle &gt; major-default, leaf-name-only
 * (findAll) path, no-match empty, and normalization (whitespace stripped, '/' preserved). ⚠️ Path examples
 * use the '>' separator; '/' is a leaf-internal character, never a separator.
 */
@ExtendWith(MockitoExtension.class)
class CoupangFeeResolverTest {

    @Mock private CoupangFeeReferenceRepository repository;
    @InjectMocks private CoupangFeeResolver resolver;

    private CoupangFeeReference row(String dae, String jung, String so, String rate) {
        return CoupangFeeReference.builder().dae(dae).jung(jung).so(so).rate(new BigDecimal(rate)).build();
    }

    @Test
    void subCategory_takesPriority() {
        given(repository.findByDae("가전디지털")).willReturn(List.of(
                row("가전디지털", "", "", "0.078"),
                row("가전디지털", "게임", "", "0.099"),
                row("가전디지털", "게임", "TV/비디오게임", "0.068")));

        Optional<BigDecimal> rate = resolver.resolve("가전디지털 > 게임 > TV/비디오게임");

        assertThat(rate).contains(new BigDecimal("0.068"));   // sub wins over middle/major
    }

    @Test
    void middleCategory_whenNoSubMatch() {
        given(repository.findByDae("가전디지털")).willReturn(List.of(
                row("가전디지털", "", "", "0.078"),
                row("가전디지털", "게임", "", "0.099"),
                row("가전디지털", "게임", "TV/비디오게임", "0.068")));

        Optional<BigDecimal> rate = resolver.resolve("가전디지털 > 게임 > 존재하지않는소분류");

        assertThat(rate).contains(new BigDecimal("0.099"));   // falls to middle-default (jung, blank so)
    }

    @Test
    void majorDefault_whenNoSubOrMiddleMatch() {
        given(repository.findByDae("가전디지털")).willReturn(List.of(
                row("가전디지털", "", "", "0.078"),
                row("가전디지털", "게임", "TV/비디오게임", "0.068")));

        Optional<BigDecimal> rate = resolver.resolve("가전디지털 > 기타리프");

        assertThat(rate).contains(new BigDecimal("0.078"));   // major default (blank jung & so)
    }

    @Test
    void leafNameAlone_scansFindAll() {
        given(repository.findAll()).willReturn(List.of(
                row("생활용품", "", "", "0.078"),
                row("생활용품", "청소/세탁", "청소/세탁/욕실용품", "0.108")));

        Optional<BigDecimal> rate = resolver.resolve("청소/세탁/욕실용품");   // single token → findAll path

        assertThat(rate).contains(new BigDecimal("0.108"));
    }

    @Test
    void noMatch_returnsEmpty() {
        given(repository.findAll()).willReturn(List.of(row("생활용품", "", "", "0.078")));

        assertThat(resolver.resolve("존재하지않는리프")).isEmpty();
    }

    @Test
    void unknownMajor_narrowsToEmpty() {
        given(repository.findByDae("없는대분류")).willReturn(List.of());

        assertThat(resolver.resolve("없는대분류 > 리프")).isEmpty();   // first token matches no dae
    }

    @Test
    void normalization_stripsWhitespaceKeepsSlashAndCase() {
        given(repository.findByDae("가전디지털")).willReturn(List.of(
                row("가전디지털", "카메라/카메라용품", "DSLR/SLR카메라", "0.058")));

        // extra spaces around tokens + trailing space; '/' inside the leaf must stay intact
        Optional<BigDecimal> rate = resolver.resolve("가전디지털 >  카메라/카메라용품  > dslr/slr카메라 ");

        assertThat(rate).contains(new BigDecimal("0.058"));
    }

    @Test
    void blankPath_returnsEmpty() {
        assertThat(resolver.resolve("   ")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
