package com.pms.service;

import com.pms.domain.PurchasePlace;
import com.pms.dto.request.PurchasePlaceRequest;
import com.pms.dto.response.PurchasePlaceResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductPurchasePlaceRepository;
import com.pms.repository.PurchasePlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-wide purchase place list (FEATURE_2609_76 / D2 · D9 · D15 · D19). Tenant isolation is automatic via
 * {@code @TenantId} on {@link PurchasePlace} — no manual tenant conditions.
 *
 * <p>⚠️ Entity is immutable (no setters): a rename rebuilds via {@code toBuilder}. Products hold the id, so a
 * rename is visible on every product with no other write (D3).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchasePlaceServiceImpl implements PurchasePlaceService {

    private final PurchasePlaceRepository purchasePlaceRepository;
    private final ProductPurchasePlaceRepository productPurchasePlaceRepository;

    /**
     * 🔴 Not read-only: an empty list is filled with {@link PurchasePlace#DEFAULT_NAMES} right here (D19).
     * There is no "already seeded" flag — a tenant that deletes every place gets the three back on the next
     * read. That is the accepted cost of D19.
     */
    @Override
    @Transactional
    public List<PurchasePlaceResponse> list() {
        List<PurchasePlace> places = purchasePlaceRepository.findAllByOrderBySortOrderAscIdAsc();
        if (places.isEmpty()) {
            List<PurchasePlace> defaults = new ArrayList<>();
            for (int i = 0; i < PurchasePlace.DEFAULT_NAMES.size(); i++) {
                defaults.add(PurchasePlace.builder().name(PurchasePlace.DEFAULT_NAMES.get(i)).sortOrder(i).build());
            }
            places = purchasePlaceRepository.saveAll(defaults);
            log.info("Seeded {} default purchase places", places.size());
        }
        Map<Long, Integer> counts = productCounts(places.stream().map(PurchasePlace::getId).toList());
        return places.stream()
                .map(place -> mapToResponse(place, counts.getOrDefault(place.getId(), 0)))
                .toList();
    }

    @Override
    @Transactional
    public PurchasePlaceResponse create(PurchasePlaceRequest request) {
        String name = request.getName().trim();
        if (purchasePlaceRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 있는 구매처 이름입니다: " + name);
        }
        List<PurchasePlace> existing = purchasePlaceRepository.findAllByOrderBySortOrderAscIdAsc();
        int nextSortOrder = existing.stream().mapToInt(PurchasePlace::getSortOrder).max().orElse(-1) + 1;
        PurchasePlace saved = purchasePlaceRepository.save(PurchasePlace.builder()
                .name(name)
                .sortOrder(nextSortOrder)
                .build());
        // A new place is used by nothing yet.
        return mapToResponse(saved, 0);
    }

    @Override
    @Transactional
    public PurchasePlaceResponse rename(Long id, PurchasePlaceRequest request) {
        PurchasePlace place = findOrThrow(id);
        String name = request.getName().trim();
        if (purchasePlaceRepository.existsByNameAndIdNot(name, id)) {
            throw new IllegalArgumentException("이미 있는 구매처 이름입니다: " + name);
        }
        PurchasePlace saved = purchasePlaceRepository.save(place.toBuilder().name(name).build());
        return mapToResponse(saved, productCounts(List.of(id)).getOrDefault(id, 0));
    }

    /**
     * Delete a place no active product uses (D9). Links left on soft-deleted products are removed with it —
     * those products are hidden and cannot be restored, and the foreign key would refuse the delete otherwise.
     */
    @Override
    @Transactional
    public void delete(Long id) {
        PurchasePlace place = findOrThrow(id);
        int inUse = productCounts(List.of(id)).getOrDefault(id, 0);
        if (inUse > 0) {
            throw new IllegalArgumentException(inUse + "개 물품이 사용 중입니다");
        }
        productPurchasePlaceRepository.deleteByPurchasePlaceId(id);
        purchasePlaceRepository.delete(place);
    }

    /** place id → active product count, one grouped query. */
    private Map<Long, Integer> productCounts(List<Long> placeIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (placeIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : productPurchasePlaceRepository.countActiveProductsByPlaceIdIn(placeIds)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private PurchasePlace findOrThrow(Long id) {
        return purchasePlaceRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PurchasePlace", id));
    }

    private PurchasePlaceResponse mapToResponse(PurchasePlace place, int productCount) {
        return PurchasePlaceResponse.builder()
                .id(place.getId())
                .name(place.getName())
                .sortOrder(place.getSortOrder())
                .productCount(productCount)
                .build();
    }
}
