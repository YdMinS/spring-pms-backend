package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CarrierRate;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.CreateProductListingRequest;
import com.pms.dto.response.ProductListingResponse;
import com.pms.dto.response.ProductListingOptionResponse;
import com.pms.dto.response.ProductListingProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * ProductListingServiceImpl - Product listing service implementation
 *
 * Handles read/update/delete of ProductListing entities with validation
 * for referenced entities (Category, CarrierRate, Package).
 *
 * 🔴 2609_71/D7: 셀을 직접 만드는 경로는 없다 — 판매상품은 마스터를 통해서만 생긴다
 * (ChannelAddServiceImpl · CoupangListingImportServiceImpl · MasterFromChannelServiceImpl).
 * 이 서비스의 update 는 마스터 미연결 셀의 이름·마켓 상품 ID·옵션 행만 고치며 구성품은 다루지 않는다.
 *
 * Transaction Management:
 * - Class-level @Transactional(readOnly = true) for all read operations
 * - Method-level @Transactional override for write operations (update, delete)
 *
 * Key Features:
 * - platformProductId uniqueness validation
 * - FK reference validation before save
 * - Immutable pattern (toBuilder) for updates
 * - Pagination with default page size
 *
 * @see ProductListingRepository for persistence
 * @see ProductListingService for interface contract
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductListingServiceImpl implements ProductListingService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    /** 셀 옵션의 구성품을 얻는 유일한 창구(2609_71) — 셀 BOM 테이블은 사라졌다. */
    private final CellBomResolver cellBomResolver;
    private final CategoryRepository categoryRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PackageRepository packageRepository;
    private final SellerRepository sellerRepository;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Retrieve a product listing by ID.
     *
     * @param id Product listing ID
     * @return ProductListingResponse with all listing details and options
     * @throws ResourceNotFoundException if listing not found
     */
    @Override
    public ProductListingResponse getById(Long id) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
        return loadProductListingWithOptions(listing);
    }

    /**
     * Retrieve all product listings for a specific platform with pagination.
     *
     * Results sorted by ID DESC (most recent first).
     * Auto-corrects invalid page size to default (20).
     *
     * @param platform Platform identifier
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param masterLinked 마스터 연결 여부 필터(2609_22/04); null = 기존 동작(전체)
     * @param search 검색어; null/공백 = 검색 없음(기존 쿼리 그대로)
     * @return Page of ProductListingResponse with options
     */
    @Override
    public Page<ProductListingResponse> getByPlatform(
            Platform platform, int page, int size, Boolean masterLinked, String search) {
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        // 검색어는 여기서 한 번만 다듬는다 — 공백만 친 검색은 "검색 없음"이지 "이름에 공백 포함"이 아니다.
        String keyword = search == null || search.isBlank() ? null : search.trim();
        // 2609_22/04: 3값 분기 — 미지정(null)은 기존 쿼리 그대로여야 한다(동작 무변경).
        Page<ProductListing> listingPage;
        if (masterLinked == null) {
            listingPage = keyword == null
                    ? productListingRepository.findByPlatform(platform, pageable)
                    : productListingRepository.searchByPlatform(platform, keyword, pageable);
        } else if (masterLinked) {
            listingPage = keyword == null
                    ? productListingRepository.findByPlatformAndMasterProductIsNotNull(platform, pageable)
                    : productListingRepository.searchByPlatformAndMasterProductIsNotNull(platform, keyword, pageable);
        } else {
            listingPage = keyword == null
                    ? productListingRepository.findByPlatformAndMasterProductIsNull(platform, pageable)
                    : productListingRepository.searchByPlatformAndMasterProductIsNull(platform, keyword, pageable);
        }
        return listingPage.map(this::loadProductListingWithOptions);
    }

    /**
     * Update an existing product listing.
     *
     * Validates:
     * 1. platformProductId uniqueness (if changed)
     * 2. All optional references exist (if provided)
     *
     * Uses immutable pattern: old instance -> toBuilder() -> new instance.
     *
     * @param id Product listing ID
     * @param request CreateProductListingRequest with updated values
     * @return Updated ProductListingResponse with options
     * @throws ResourceNotFoundException if listing not found
     * @throws IllegalArgumentException if new platformProductId already exists
     */
    @Override
    @Transactional
    public ProductListingResponse update(Long id, CreateProductListingRequest request) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));

        // 2609_22/D32: 마스터에 연결된 셀은 legacy 경로로 수정할 수 없다. 이 update 는 옵션을 전부
        // delete + recreate 하므로 FK·platformOptionId·approvalStatus·priceSource 가 통째로 사라진다.
        // 마스터 미연결 셀은 계속 허용한다(마켓 상품 ID 오타를 고칠 유일한 창구, D29).
        if (listing.getMasterProduct() != null) {
            throw new IllegalArgumentException("마스터에 연결된 판매상품은 마스터 상세에서 수정하세요");
        }

        // Check uniqueness of new platformProductId if changed
        if (!listing.getPlatformProductId().equals(request.getPlatformProductId())) {
            if (productListingRepository.existsByPlatformProductId(request.getPlatformProductId())) {
                throw new IllegalArgumentException(
                        "Product listing with platformProductId '" + request.getPlatformProductId() + "' already exists"
                );
            }
        }

        // Resolve seller if changed
        Seller seller = listing.getSeller();
        if (!listing.getSeller().getId().equals(request.getSellerId())) {
            seller = sellerRepository.findById(request.getSellerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seller", request.getSellerId()));
        }

        // Resolve optional references
        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        }

        CarrierRate delivery = null;
        if (request.getDeliveryId() != null) {
            delivery = carrierRateRepository.findById(request.getDeliveryId())
                    .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", request.getDeliveryId()));
        }

        Package pkg = null;
        if (request.getPackageId() != null) {
            pkg = packageRepository.findById(request.getPackageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Package", request.getPackageId()));
        }

        // Update using immutable pattern
        ProductListing updated = listing.toBuilder()
                .seller(seller)
                .platform(Platform.from(request.getPlatform()))
                .platformProductId(request.getPlatformProductId())
                .name(request.getName())
                .category(category)
                .delivery(delivery)
                .package_(pkg)
                .build();

        ProductListing saved = productListingRepository.save(updated);

        // Update options: delete old ones and create new ones.
        // 2609_71: 구성품은 마스터 옵션이 갖는다 — 이 경로는 옵션 행만 다시 만든다.
        productListingOptionRepository.deleteByProductListingId(saved.getId());

        if (request.getOptions() != null && !request.getOptions().isEmpty()) {
            for (CreateProductListingRequest.OptionRequest optionReq : request.getOptions()) {
                // 2609_22/D1: this legacy API knows no master, so masterProductOption stays null — every option
                // created here is a channel-only option (D2) and master propagation will not touch it. New code
                // creates cells through the channel-add path (ChannelAddServiceImpl), which sets the FK.
                ProductListingOption option = ProductListingOption.builder()
                        .productListing(saved)
                        .optionName(optionReq.getOptionName())
                        .sellingPrice(optionReq.getSellingPrice())
                        .platformOptionId(optionReq.getPlatformOptionId())
                        .build();
                productListingOptionRepository.save(option);
            }
        }

        return loadProductListingWithOptions(saved);
    }

    /**
     * Delete a product listing.
     *
     * Cascades to child rows in FK order (options → listing).
     *
     * @param id Product listing ID
     * @throws ResourceNotFoundException if listing not found
     */
    @Override
    @Transactional
    public void delete(Long id) {
        ProductListing listing = productListingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));

        // 2609_22/D32: 위 update 와 같은 이유 — 마스터에 연결된 셀은 마스터 상세에서만 다룬다.
        if (listing.getMasterProduct() != null) {
            throw new IllegalArgumentException("마스터에 연결된 판매상품은 삭제할 수 없습니다");
        }

        // Delete in FK order: options -> listing (2609_71: 셀 구성품 행은 더 이상 없다).
        productListingOptionRepository.deleteByProductListingId(id);
        productListingRepository.delete(listing);
    }

    /**
     * Helper method to load a ProductListing with all its options and products.
     * Constructs a ProductListingResponse with nested option and product information.
     *
     * @param listing The ProductListing to load options for
     * @return ProductListingResponse with populated options and products
     */
    private ProductListingResponse loadProductListingWithOptions(ProductListing listing) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listing.getId());

        // 2609_71: 구성품은 마스터를 타고 읽는다(응답 DTO 모양은 그대로).
        // 🔴 이 legacy 경로가 만드는 옵션은 전부 채널 전용(2609_22/D1 — masterProductOption 이 null)이므로
        //    구성품 칸은 비어 나간다. 필드가 사라진 게 아니라 값을 알 수 없다는 뜻이다(PLAN D10).
        Map<Long, CellBomResolver.Bom> boms = cellBomResolver.forOptions(options);
        java.util.List<ProductListingOptionResponse> optionResponses = options.stream()
                .map(option -> {
                    CellBomResolver.Bom bom = boms.getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED);
                    java.util.List<ProductListingProductResponse> productResponses = bom.lines().stream()
                            .map(line -> ProductListingProductResponse.builder()
                                    .id(line.id())
                                    .productListingOptionId(option.getId())
                                    .productId(line.productId())
                                    .productName(line.productName())
                                    .quantity(line.quantity())
                                    .build())
                            .toList();
                    return ProductListingOptionResponse.of(option, productResponses);
                })
                .toList();

        return ProductListingResponse.of(listing, optionResponses);
    }
}
