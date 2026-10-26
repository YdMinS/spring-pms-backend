package com.pms.service.collage;

import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage-argument checks shared by the collage stages (FEATURE_2610_21) and the thumbnail photo stages
 * (FEATURE_2610_32 D23) — the one place a {@code stage + sellerId + listingId} triple is validated against a master.
 *
 * <ul>
 *   <li>{@code MASTER} ignores {@code sellerId}/{@code listingId}.</li>
 *   <li>{@code SELLER} needs a seller that has a listing of this master — otherwise 400.</li>
 *   <li>{@code LISTING} needs a listing of this master — otherwise 404.</li>
 * </ul>
 *
 * <p>Usage: {@code CollageStageTarget t = collageStageTargets.resolve(masterId, stage, sellerId, listingId);}</p>
 *
 * <p>❌ Do not re-implement these checks in a stage service. File: {@code service/collage/CollageStageTargets.java}.</p>
 */
@Component
@RequiredArgsConstructor
public class CollageStageTargets {

    private final ProductListingRepository productListingRepository;

    /** The validated stage of this master (messages = FEATURE_2610_21 R8). */
    public CollageStageTarget resolve(Long masterId, CollageStage stage, Long sellerId, Long listingId) {
        if (stage == null) {
            throw new IllegalArgumentException("단계를 골라 주세요.");
        }
        return switch (stage) {
            case MASTER -> new CollageStageTarget(CollageStage.MASTER, CollageScopes.MASTER, null, null);
            case SELLER -> {
                if (sellerId == null) {
                    throw new IllegalArgumentException("판매자를 골라 주세요.");
                }
                Seller seller = listingsBySeller(masterId).keySet().stream()
                        .filter(s -> sellerId.equals(s.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("이 마스터의 판매상품이 없는 판매자입니다."));
                yield new CollageStageTarget(CollageStage.SELLER, CollageScopes.seller(sellerId), seller, null);
            }
            case LISTING -> {
                if (listingId == null) {
                    throw new IllegalArgumentException("판매상품을 골라 주세요.");
                }
                ProductListing listing = requireListing(masterId, listingId);
                yield new CollageStageTarget(CollageStage.LISTING, CollageScopes.listing(listingId),
                        listing.getSeller(), listing);
            }
        };
    }

    /** A listing of this master (tenant-scoped) — 404 otherwise. */
    public ProductListing requireListing(Long masterId, Long listingId) {
        return productListingRepository.findScopedById(listingId)
                .filter(l -> l.getMasterProduct() != null && masterId.equals(l.getMasterProduct().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
    }

    /** The master's listings grouped by seller — sellers and listings ascending id, listings without a seller left out. */
    public Map<Seller, List<ProductListing>> listingsBySeller(Long masterId) {
        List<ProductListing> listings = new ArrayList<>(productListingRepository.findByMasterProductId(masterId));
        listings.sort(Comparator.comparing(ProductListing::getId));
        Map<Long, Seller> sellers = new HashMap<>();
        Map<Long, List<ProductListing>> byId = new HashMap<>();
        for (ProductListing listing : listings) {
            Seller seller = listing.getSeller();
            if (seller == null) {
                continue;
            }
            sellers.putIfAbsent(seller.getId(), seller);
            byId.computeIfAbsent(seller.getId(), k -> new ArrayList<>()).add(listing);
        }
        Map<Seller, List<ProductListing>> out = new LinkedHashMap<>();
        byId.keySet().stream().sorted().forEach(id -> out.put(sellers.get(id), byId.get(id)));
        return out;
    }
}
