package com.pms.service.packing;

import com.pms.domain.BoxRecipe;
import com.pms.domain.Package;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.BoxRecipeRepository;
import com.pms.repository.PackageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default {@link BoxRecipeService} (FEATURE_2609_40 / PLAN D22 · D23 · D24).
 *
 * <p>🔴 Every key in this class comes from {@link BoxRecipeKey} — never build the string inline.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoxRecipeServiceImpl implements BoxRecipeService {

    /** PLAN D23: showing a 4th choice does not help someone holding a box in their hands. */
    private static final int MAX_CANDIDATES = 3;

    private final BoxRecipeRepository boxRecipeRepository;
    private final PackageRepository packageRepository;

    @Override
    public List<BoxCandidate> candidates(List<RecipeItem> items) {
        String key = BoxRecipeKey.of(items);
        if (key.isEmpty()) {
            return List.of();
        }

        List<BoxRecipe> recipes = boxRecipeRepository.findByRecipeKeyOrderByUseCountDescLastUsedAtDesc(key);
        if (recipes.isEmpty()) {
            // Unknown combination is a normal state, not an error (D23) — the screen says
            // "적합한 상자를 찾지 못했습니다" and the worker picks a box from the full list.
            return List.of();
        }

        // The write path remembers every box, including ones later removed (D24). Reload the boxes so that
        // only ones that still exist for this tenant are offered — a candidate must be usable right now.
        Set<Long> packageIds = recipes.stream()
                .map(recipe -> recipe.getBoxPackage().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Package> usable = packageRepository.findAllById(packageIds).stream()
                .collect(Collectors.toMap(Package::getId, Function.identity()));

        List<BoxCandidate> candidates = new ArrayList<>();
        for (BoxRecipe recipe : recipes) {
            Package box = usable.get(recipe.getBoxPackage().getId());
            if (box == null) {
                continue;
            }
            candidates.add(new BoxCandidate(
                    box.getId(), box.getType(), box.getBoxKind(), box.getCost(),
                    box.getWidthCm(), box.getLengthCm(), box.getHeightCm(), box.getImageUrl(),
                    recipe.getUseCount(), recipe.getLastUsedAt()));
            if (candidates.size() == MAX_CANDIDATES) {
                break;
            }
        }
        return candidates;
    }

    @Override
    @Transactional
    public void remember(List<RecipeItem> items, Long packageId) {
        String key = BoxRecipeKey.of(items);
        if (key.isEmpty() || packageId == null) {
            // Nothing was packed, or no box was chosen — there is no combination to remember.
            return;
        }

        BoxRecipe existing = boxRecipeRepository.findByRecipeKeyAndBoxPackage_Id(key, packageId).orElse(null);
        if (existing != null) {
            // toBuilder(), never a hand-copied builder: a hand copy silently drops the columns it forgets.
            boxRecipeRepository.save(existing.toBuilder()
                    .useCount(existing.getUseCount() + 1)
                    .lastUsedAt(LocalDateTime.now())
                    .build());
            return;
        }

        Package box = packageRepository.findById(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", packageId));
        boxRecipeRepository.save(BoxRecipe.builder()
                .recipeKey(key)
                .boxPackage(box)
                .useCount(1)
                .lastUsedAt(LocalDateTime.now())
                .build()); // tenant_id is filled by @TenantId — never set by hand
    }
}
