package com.pms.service.packing;

import com.pms.domain.BoxKind;
import com.pms.domain.BoxRecipe;
import com.pms.domain.Package;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.PackageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Box memory (FEATURE_2609_40 / PLAN D22 · D23 · D24).
 *
 * <p>🔴 The key rule is the point of this class: the same combination must produce the same key no matter
 * what order the items were scanned in, and a different quantity must produce a different key. If either
 * broke, the memory would silently never match again.</p>
 */
@ExtendWith(MockitoExtension.class)
class BoxRecipeServiceTest {

    @Mock private BoxRecipeRepository boxRecipeRepository;
    @Mock private PackageRepository packageRepository;
    @InjectMocks private BoxRecipeServiceImpl service;

    private Package box(long id, String type) {
        return Package.builder()
                .id(id).type(type).cost(new BigDecimal("300"))
                .widthCm(new BigDecimal("22.0")).lengthCm(new BigDecimal("19.0")).heightCm(new BigDecimal("9.0"))
                .boxKind(BoxKind.PURCHASED)
                .build();
    }

    private BoxRecipe recipe(Package box, int useCount) {
        return BoxRecipe.builder()
                .recipeKey("12:2|45:1").boxPackage(box).useCount(useCount)
                .lastUsedAt(LocalDateTime.now().minusDays(useCount))
                .build();
    }

    // ---- candidates ----

    @Test
    void testCandidatesOrderedByUseCount() {
        Package a = box(1L, "소박스");
        Package b = box(2L, "중박스");
        Package c = box(3L, "대박스");
        Package d = box(4L, "특대박스");
        // The repository query already orders by use count desc, then last used desc.
        given(boxRecipeRepository.findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(anyString()))
                .willReturn(List.of(recipe(a, 9), recipe(b, 5), recipe(c, 2), recipe(d, 1)));
        given(packageRepository.findAllById(any())).willReturn(List.of(a, b, c, d));

        List<BoxCandidate> candidates = service.candidates(List.of(new RecipeItem(12L, 2), new RecipeItem(45L, 1)));

        // 3 is the cap (D23): a 4th choice does not help someone holding a box.
        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(BoxCandidate::packageId).containsExactly(1L, 2L, 3L);
        assertThat(candidates).extracting(BoxCandidate::useCount).containsExactly(9, 5, 2);
        assertThat(candidates.get(0).type()).isEqualTo("소박스");
        assertThat(candidates.get(0).boxKind()).isEqualTo(BoxKind.PURCHASED);
    }

    @Test
    void testRecipeKeyIsOrderIndependent() {
        given(boxRecipeRepository.findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(anyString()))
                .willReturn(List.of());

        service.candidates(List.of(new RecipeItem(45L, 1), new RecipeItem(12L, 2)));
        service.candidates(List.of(new RecipeItem(12L, 2), new RecipeItem(45L, 1)));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(boxRecipeRepository, times(2))
                .findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(keys.capture());
        // 🔴 Same combination, different scan order → byte-identical key, sorted by product id.
        assertThat(keys.getAllValues().get(0)).isEqualTo("12:2|45:1");
        assertThat(keys.getAllValues().get(1)).isEqualTo("12:2|45:1");
    }

    @Test
    void testRecipeKeyDistinguishesQuantity() {
        given(boxRecipeRepository.findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(anyString()))
                .willReturn(List.of());

        service.candidates(List.of(new RecipeItem(12L, 2), new RecipeItem(45L, 1)));
        service.candidates(List.of(new RecipeItem(12L, 3), new RecipeItem(45L, 1)));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(boxRecipeRepository, times(2))
                .findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo("12:2|45:1");
        assertThat(keys.getAllValues().get(1)).isEqualTo("12:3|45:1");
    }

    @Test
    void testCandidatesEmptyWhenUnknown() {
        given(boxRecipeRepository.findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(anyString()))
                .willReturn(List.of());

        List<BoxCandidate> candidates = service.candidates(List.of(new RecipeItem(7L, 1)));

        // No memory is a normal state, not an error — the screen says "적합한 상자를 찾지 못했습니다" (D23).
        assertThat(candidates).isEmpty();
    }

    // ---- remember ----

    @Test
    void testRememberIncrementsUseCount() {
        Package a = box(1L, "소박스");
        List<RecipeItem> items = List.of(new RecipeItem(12L, 2), new RecipeItem(45L, 1));

        // First time: nothing remembered yet → a new row with use count 1.
        given(boxRecipeRepository.findByRecipeKeyAndBoxPackage_Id("12:2|45:1", 1L)).willReturn(Optional.empty());
        given(packageRepository.findById(1L)).willReturn(Optional.of(a));
        service.remember(items, 1L);

        ArgumentCaptor<BoxRecipe> first = ArgumentCaptor.forClass(BoxRecipe.class);
        verify(boxRecipeRepository).save(first.capture());
        assertThat(first.getValue().getUseCount()).isEqualTo(1);
        assertThat(first.getValue().getRecipeKey()).isEqualTo("12:2|45:1");

        // Second time, same combination and same box → the existing row climbs to 2.
        given(boxRecipeRepository.findByRecipeKeyAndBoxPackage_Id("12:2|45:1", 1L))
                .willReturn(Optional.of(first.getValue()));
        service.remember(items, 1L);

        ArgumentCaptor<BoxRecipe> second = ArgumentCaptor.forClass(BoxRecipe.class);
        verify(boxRecipeRepository, times(2)).save(second.capture());
        assertThat(second.getAllValues().get(1).getUseCount()).isEqualTo(2);
        // Never a second row for the same (key, box) — the unique constraint would reject it anyway.
        verify(packageRepository, times(1)).findById(eq(1L));
    }
}
