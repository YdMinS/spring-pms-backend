package com.pms.service.collage;

import com.pms.domain.CollageLayout;
import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailCollage;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.CollagePreviewRequest;
import com.pms.dto.request.CollageSaveRequest;
import com.pms.dto.response.CollageOverviewResponse;
import com.pms.dto.response.CollagePreviewTarget;
import com.pms.dto.response.CollageProductView;
import com.pms.dto.response.CollageStageResponse;
import com.pms.dto.response.CollageStageView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ThumbnailCollageRepository;
import com.pms.service.ChannelTemplateResolver;
import com.pms.service.ListingAssetService;
import com.pms.service.MasterProductService;
import com.pms.service.ThumbnailRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ThumbnailCollageService} (FEATURE_2610_21). Stage resolution is {@link ThumbnailCollageResolver#pick}
 * (listing ?? seller ?? master, D37); layouts are adjusted on read (D43); validation is
 * {@link CollageLayouts#validate}; preview rendering is {@link CollageThumbnailRenderer} (D35).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThumbnailCollageServiceImpl implements ThumbnailCollageService {

    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final ThumbnailCollageRepository thumbnailCollageRepository;
    private final ThumbnailCollageResolver thumbnailCollageResolver;
    private final MasterProductService masterProductService;
    private final ChannelTemplateResolver channelTemplateResolver;
    private final ListingAssetService listingAssetService;
    private final CollageThumbnailRenderer collageThumbnailRenderer;

    /** A validated stage argument: its key, and the seller / listing it points at. */
    private record Target(CollageStage stage, String scopeKey, Seller seller, ProductListing listing) {
    }

    @Override
    public CollageOverviewResponse overview(Long masterId) {
        MasterProduct master = requireMaster(masterId);
        List<Product> products = thumbnailCollageResolver.components(master.getId());
        Map<String, ThumbnailCollage> rows = thumbnailCollageResolver.rowsByScopeKey(master.getId());
        List<CollageStageView> stages = new ArrayList<>();
        stages.add(view(new Target(CollageStage.MASTER, CollageScopes.MASTER, null, null), rows));
        for (Map.Entry<Seller, List<ProductListing>> entry : listingsBySeller(master.getId()).entrySet()) {
            Seller seller = entry.getKey();
            stages.add(view(new Target(CollageStage.SELLER, CollageScopes.seller(seller.getId()), seller, null), rows));
            for (ProductListing listing : entry.getValue()) {
                stages.add(view(new Target(CollageStage.LISTING, CollageScopes.listing(listing.getId()), seller,
                        listing), rows));
            }
        }
        return new CollageOverviewResponse(masterProductService.isBundle(master.getId()), productViews(products),
                stages);
    }

    @Override
    public CollageStageResponse getStage(Long masterId, CollageStage stage, Long sellerId, Long listingId) {
        MasterProduct master = requireMaster(masterId);
        return stageResponse(master, target(master.getId(), stage, sellerId, listingId));
    }

    @Override
    @Transactional
    public CollageStageResponse save(Long masterId, CollageStage stage, Long sellerId, Long listingId,
                                     CollageSaveRequest request) {
        MasterProduct master = requireMaster(masterId);
        Target target = target(master.getId(), stage, sellerId, listingId);
        if (!masterProductService.isBundle(master.getId())) {
            throw new IllegalArgumentException("구성품이 2개 이상인 마스터만 콜라주를 만들 수 있습니다.");
        }
        if (request == null || request.getEnabled() == null) {
            throw new IllegalArgumentException("콜라주 켜기·끄기 값이 없습니다.");
        }
        boolean enabled = request.getEnabled();
        CollageLayout layout = null;
        if (enabled) {
            List<Long> ids = componentIds(master.getId());
            CollageLayouts.validate(request.getLayout(), ids);
            layout = request.getLayout();
        }
        ThumbnailCollage existing = thumbnailCollageRepository
                .findByMasterProduct_IdAndScopeKey(master.getId(), target.scopeKey()).orElse(null);
        ThumbnailCollage toSave = existing != null
                ? existing.toBuilder().enabled(enabled).layout(layout).build()
                : ThumbnailCollage.builder()
                        .masterProduct(master)
                        .seller(target.stage() == CollageStage.SELLER ? target.seller() : null)
                        .productListing(target.listing())
                        .scopeKey(target.scopeKey())
                        .enabled(enabled)
                        .layout(layout)
                        .build();
        thumbnailCollageRepository.save(toSave);
        return stageResponse(master, target);
    }

    @Override
    @Transactional
    public CollageStageResponse delete(Long masterId, CollageStage stage, Long sellerId, Long listingId) {
        MasterProduct master = requireMaster(masterId);
        Target target = target(master.getId(), stage, sellerId, listingId);
        thumbnailCollageRepository.findByMasterProduct_IdAndScopeKey(master.getId(), target.scopeKey())
                .ifPresent(thumbnailCollageRepository::delete);
        thumbnailCollageRepository.flush();
        return stageResponse(master, target);
    }

    @Override
    public byte[] preview(Long masterId, CollagePreviewRequest request) {
        MasterProduct master = requireMaster(masterId);
        if (!masterProductService.isBundle(master.getId())) {
            throw new IllegalArgumentException("구성품이 2개 이상인 마스터만 콜라주를 만들 수 있습니다.");
        }
        List<Product> products = thumbnailCollageResolver.components(master.getId());
        CollageLayout layout = request == null ? null : request.getLayout();
        CollageLayouts.validate(layout, products.stream().map(Product::getId).toList());
        if (request.getListingId() != null) {
            ProductListing listing = requireListing(master.getId(), request.getListingId());
            return listingAssetService.previewCollage(listing, layout, products);
        }
        ThumbnailTemplate template = channelTemplateResolver.defaultThumbnail();
        return collageThumbnailRenderer.render(template, fieldDefaults(template), layout, products);
    }

    // ---------------------------------------------------------------- helpers

    private MasterProduct requireMaster(Long masterId) {
        return masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));
    }

    private ProductListing requireListing(Long masterId, Long listingId) {
        return productListingRepository.findScopedById(listingId)
                .filter(l -> l.getMasterProduct() != null && masterId.equals(l.getMasterProduct().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
    }

    private Target target(Long masterId, CollageStage stage, Long sellerId, Long listingId) {
        if (stage == null) {
            throw new IllegalArgumentException("단계를 골라 주세요.");
        }
        return switch (stage) {
            case MASTER -> new Target(CollageStage.MASTER, CollageScopes.MASTER, null, null);
            case SELLER -> {
                if (sellerId == null) {
                    throw new IllegalArgumentException("판매자를 골라 주세요.");
                }
                Seller seller = listingsBySeller(masterId).keySet().stream()
                        .filter(s -> sellerId.equals(s.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("이 마스터의 판매상품이 없는 판매자입니다."));
                yield new Target(CollageStage.SELLER, CollageScopes.seller(sellerId), seller, null);
            }
            case LISTING -> {
                if (listingId == null) {
                    throw new IllegalArgumentException("판매상품을 골라 주세요.");
                }
                ProductListing listing = requireListing(masterId, listingId);
                yield new Target(CollageStage.LISTING, CollageScopes.listing(listingId), listing.getSeller(),
                        listing);
            }
        };
    }

    /** The master's listings grouped by seller — sellers and listings ascending id. */
    private Map<Seller, List<ProductListing>> listingsBySeller(Long masterId) {
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

    private CollageStageResponse stageResponse(MasterProduct master, Target target) {
        Long masterId = master.getId();
        List<Product> products = thumbnailCollageResolver.components(masterId);
        List<Long> ids = products.stream().map(Product::getId).toList();
        boolean bundle = masterProductService.isBundle(masterId);
        Map<String, ThumbnailCollage> rows = thumbnailCollageResolver.rowsByScopeKey(masterId);
        ThumbnailCollage own = rows.get(target.scopeKey());
        ThumbnailCollage upper = upperRow(rows, target);
        CollageLayout ownLayout = bundle && enabledWithLayout(own) ? CollageLayouts.adjust(own.getLayout(), ids) : null;
        CollageLayout inherited = bundle && enabledWithLayout(upper)
                ? CollageLayouts.adjust(upper.getLayout(), ids) : null;
        return new CollageStageResponse(view(target, rows), bundle, productViews(products),
                CollageLayouts.shapesFor(bundle ? ids.size() : 0), ownLayout, inherited,
                previewTargets(masterId, target, rows));
    }

    private static boolean enabledWithLayout(ThumbnailCollage row) {
        return row != null && Boolean.TRUE.equals(row.getEnabled()) && row.getLayout() != null;
    }

    /** The nearest stored stage above {@code target}: listing → seller ?? master · seller → master · master → none. */
    private static ThumbnailCollage upperRow(Map<String, ThumbnailCollage> rows, Target target) {
        return switch (target.stage()) {
            case MASTER -> null;
            case SELLER -> rows.get(CollageScopes.MASTER);
            case LISTING -> {
                ThumbnailCollage seller = target.seller() == null ? null
                        : rows.get(CollageScopes.seller(target.seller().getId()));
                yield seller != null ? seller : rows.get(CollageScopes.MASTER);
            }
        };
    }

    private static CollageStageView view(Target target, Map<String, ThumbnailCollage> rows) {
        ThumbnailCollage own = rows.get(target.scopeKey());
        CollageSource source;
        ThumbnailCollage used;
        if (own != null) {
            source = CollageSource.OWN;
            used = own;
        } else {
            used = upperRow(rows, target);
            if (used == null) {
                source = CollageSource.NONE;
            } else {
                source = CollageScopes.MASTER.equals(used.getScopeKey()) ? CollageSource.MASTER : CollageSource.SELLER;
            }
        }
        Seller seller = target.seller();
        ProductListing listing = target.listing();
        return new CollageStageView(
                target.stage(),
                seller == null ? null : seller.getId(),
                seller == null ? null : seller.getSellerName(),
                listing == null ? null : listing.getId(),
                listing == null || listing.getPlatform() == null ? null : listing.getPlatform().name(),
                source,
                used == null ? null : used.getEnabled());
    }

    /**
     * D46: listing stage = that listing; seller stage = the seller's listings without their own row; master stage =
     * listings with neither their own nor their seller's row — ascending id. None → the tenant default template.
     */
    private List<CollagePreviewTarget> previewTargets(Long masterId, Target target,
                                                      Map<String, ThumbnailCollage> rows) {
        List<ProductListing> users = new ArrayList<>();
        if (target.stage() == CollageStage.LISTING) {
            users.add(target.listing());
        } else {
            for (List<ProductListing> listings : listingsBySeller(masterId).values()) {
                for (ProductListing listing : listings) {
                    boolean ownRow = rows.containsKey(CollageScopes.listing(listing.getId()));
                    boolean sellerRow = rows.containsKey(CollageScopes.seller(listing.getSeller().getId()));
                    boolean uses = target.stage() == CollageStage.SELLER
                            ? listing.getSeller().getId().equals(target.seller().getId()) && !ownRow
                            : !ownRow && !sellerRow;
                    if (uses) {
                        users.add(listing);
                    }
                }
            }
            users.sort(Comparator.comparing(ProductListing::getId));
        }
        List<CollagePreviewTarget> out = new ArrayList<>();
        for (ProductListing listing : users) {
            out.add(previewTarget(listing, channelTemplateResolver.resolveThumbnail(listing)));
        }
        if (out.isEmpty()) {
            out.add(previewTarget(null, channelTemplateResolver.defaultThumbnail()));
        }
        return out;
    }

    private static CollagePreviewTarget previewTarget(ProductListing listing, ThumbnailTemplate template) {
        TemplateElement slot = ThumbnailRenderer.firstProductImageElement(template.getElements());
        TemplateElement.Region r = slot == null ? null : slot.getRegion();
        boolean hasSlot = r != null && r.getW() != null && r.getH() != null && r.getW() > 0 && r.getH() > 0;
        return new CollagePreviewTarget(
                listing == null ? null : listing.getId(),
                listing == null || listing.getSeller() == null ? null : listing.getSeller().getSellerName(),
                listing == null || listing.getPlatform() == null ? null : listing.getPlatform().name(),
                template.getCanvasWidth() == null ? 0 : template.getCanvasWidth(),
                template.getCanvasHeight() == null ? 0 : template.getCanvasHeight(),
                hasSlot ? r.getW() : null,
                hasSlot ? r.getH() : null);
    }

    private List<Long> componentIds(Long masterId) {
        return thumbnailCollageResolver.components(masterId).stream().map(Product::getId).toList();
    }

    private static List<CollageProductView> productViews(List<Product> products) {
        return products.stream()
                .map(p -> new CollageProductView(p.getId(), p.getProductName(),
                        StringUtils.hasText(p.getImageUrl()) ? p.getImageUrl() : null))
                .toList();
    }

    /** Template field defaults (non-blank) — the text of a preview without a listing (rehearsal ⑤). */
    private static Map<String, String> fieldDefaults(ThumbnailTemplate template) {
        Map<String, String> bindings = new HashMap<>();
        if (template.getFields() == null) {
            return bindings;
        }
        for (TemplateField field : template.getFields()) {
            if (StringUtils.hasText(field.getDefaultValue())) {
                bindings.put(field.getKey(), field.getDefaultValue());
            }
        }
        return bindings;
    }
}
