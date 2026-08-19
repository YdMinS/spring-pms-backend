package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingTagRevision;
import com.pms.repository.ProductListingTagRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tag merge + revision recording (33): channel-first master-append merge, platform cap truncation, and
 * change-detected append-only recording. Pure logic — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class TagMergeServiceTest {

    @Mock
    private ProductListingTagRevisionRepository revisionRepository;

    @InjectMocks
    private TagMergeServiceImpl service;

    private ProductListing cell(String platform, List<String> channelTags, List<String> masterTags) {
        MasterProduct master = masterTags == null ? null
                : MasterProduct.builder().tags(masterTags).build();
        return ProductListing.builder()
                .id(1L).platform(platform).masterProduct(master).tags(channelTags)
                .build();
    }

    @Test
    void resolve_채널먼저_마스터append_중복제외() {
        List<String> merged = service.resolveTags(
                cell("COUPANG", List.of("a", "b"), List.of("b", "c")));

        assertThat(merged).containsExactly("a", "b", "c");
    }

    @Test
    void resolve_쿠팡상한20_초과절단() {
        List<String> twentyFive = IntStream.range(0, 25).mapToObj(i -> "t" + i).toList();

        List<String> merged = service.resolveTags(cell("COUPANG", twentyFive, null));

        assertThat(merged).hasSize(20);
    }

    @Test
    void recordRevision_변동시insert() {
        given(revisionRepository.findTopByProductListing_IdOrderByIdDesc(1L))
                .willReturn(Optional.of(ProductListingTagRevision.builder().tags(List.of("old")).build()));

        service.recordRevisionIfChanged(cell("COUPANG", null, null), List.of("a", "b"));

        verify(revisionRepository).save(any(ProductListingTagRevision.class));
    }

    @Test
    void recordRevision_동일시skip() {
        given(revisionRepository.findTopByProductListing_IdOrderByIdDesc(1L))
                .willReturn(Optional.of(ProductListingTagRevision.builder().tags(List.of("a", "b")).build()));

        service.recordRevisionIfChanged(cell("COUPANG", null, null), List.of("a", "b"));

        verify(revisionRepository, never()).save(any());
    }

    @Test
    void dedup_순서유지_중복제거() {
        assertThat(service.dedup(List.of("a", "b", "a", "c"))).containsExactly("a", "b", "c");
    }
}
