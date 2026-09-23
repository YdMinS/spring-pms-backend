package com.pms.service;

import com.pms.domain.PriceChangeReason;
import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import com.pms.service.price.PriceHistoryRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * ProductServiceImpl - Product service implementation
 *
 * Phase 2-1 TDD CREATE: Implements only create() and supporting helper methods
 * - create(CreateProductRequest request): Creates new product
 * - validatePrice(BigDecimal price): Validates price > 0
 * - validateNetContentUnit(String, String): Validates netContentUnit in [KG, G, L, ML]
 * - mapToResponse(Product product): Maps Product entity to ProductResponse
 *
 * Other CRUD methods (getProduct, getAllProducts, updateProduct, deleteProduct)
 * will be added in subsequent phases (2-2, 2-3, 2-4, 2-5) following TDD pattern
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final PriceHistoryRecorder priceHistoryRecorder;
    private final ProductUsageService productUsageService;
    private static final String[] VALID_NET_CONTENT_UNITS = {"KG", "G", "L", "ML"};
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        // Validate request
        if (request.getPrice() != null) {
            validatePrice(request.getPrice());
        }
        validateNetContentUnit(request.getNetContentUnit(), request.getNetContent());

        String barcodeId = normalizeBarcode(request.getBarcodeId());
        assertBarcodeFree(barcodeId, null);

        // Build product using immutable pattern
        Product product = Product.builder()
                .barcodeId(barcodeId)
                .brand(request.getBrand())
                .price(request.getPrice())
                .productName(request.getProductName())
                .store(request.getStore())
                .netContentUnit(request.getNetContentUnit())
                .packageHeight(request.getPackageHeight())
                .packageLength(request.getPackageLength())
                .packageWidth(request.getPackageWidth())
                .netContent(request.getNetContent())
                .description(request.getDescription())
                .active(true)
                .build();

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    public ProductResponse getProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        return mapToResponse(product);
    }

    @Override
    public Page<ProductResponse> getAllProducts(int page, int size, String search) {
        // Validate and adjust page size
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        // Create pageable with createdDate DESC sorting
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Fetch products
        Page<Product> productPage;
        if (search == null || search.trim().isEmpty()) {
            productPage = productRepository.findByActiveTrue(pageable);
        } else {
            productPage = productRepository.searchByKeyword(search.trim(), pageable);
        }

        // Convert to response
        return productPage.map(this::mapToResponse);
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        // Find product by id
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // Check if product is active
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // Validate updates
        request.getPrice().ifPresent(this::validatePrice);

        // Price history hook ② (PLAN 2609_28 D23) — captured BEFORE the immutable rebuild below.
        // This is the hole that purchase_record cannot fill: a cost edited by hand here leaves no
        // other trace at all.
        BigDecimal oldPrice = product.getPrice();

        // Get final unit and net content after merging with existing product
        String finalUnit = request.getNetContentUnit().orElse(product.getNetContentUnit());
        String finalNetContent = request.getNetContent().orElse(product.getNetContent());
        validateNetContentUnit(finalUnit, finalNetContent);

        // ⚠️ Only checked when the request actually carried a barcode. An edit that never mentions it must
        // not fail on a duplicate somebody else created earlier — otherwise a legacy clash would freeze
        // every other field of both products. `id` is excluded, so resending one's own code is a no-op.
        String finalBarcode = product.getBarcodeId();
        if (request.getBarcodeId().isPresent()) {
            finalBarcode = normalizeBarcode(request.getBarcodeId().get());
            assertBarcodeFree(finalBarcode, id);
        }

        // Build updated product using immutable pattern - use toBuilder to preserve audit fields
        Product updated = product.toBuilder()
                .barcodeId(finalBarcode)
                .brand(request.getBrand().orElse(product.getBrand()))
                .price(request.getPrice().orElse(product.getPrice()))
                .productName(request.getProductName().orElse(product.getProductName()))
                .store(request.getStore().orElse(product.getStore()))
                .netContentUnit(request.getNetContentUnit().orElse(product.getNetContentUnit()))
                .packageHeight(request.getPackageHeight().orElse(product.getPackageHeight()))
                .packageLength(request.getPackageLength().orElse(product.getPackageLength()))
                .packageWidth(request.getPackageWidth().orElse(product.getPackageWidth()))
                .netContent(request.getNetContent().orElse(product.getNetContent()))
                .description(request.getDescription().orElse(product.getDescription()))
                .build();

        // Save updated product
        Product saved = productRepository.save(updated);
        // ⚠️ Only when `price` was actually sent: Optional.empty() = field omitted, and an edit that
        // never mentions the price is not a price change (the recorder also drops equal values).
        request.getPrice().ifPresent(newPrice -> priceHistoryRecorder.recordProductCost(
                saved, oldPrice, newPrice, PriceChangeReason.PRODUCT_EDIT, null));
        return mapToResponse(saved);
    }

    /**
     * Trim a barcode and turn a blank one into {@code null}.
     *
     * <p>🔴 "" and null must not be two different states: the unique key ignores NULLs but would treat
     * empty strings as ordinary colliding values, so the second product saved with a blank barcode would
     * fail with a database error instead of simply having no barcode. Sending {@code ""} on an update is
     * therefore also the way to CLEAR a barcode.</p>
     */
    private String normalizeBarcode(String barcodeId) {
        if (barcodeId == null) {
            return null;
        }
        String trimmed = barcodeId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Refuse a barcode another product in this tenant already owns (409).
     *
     * <p>Mirrors the {@code uq_products_tenant_barcode} key (changeset 098) so the user gets a readable
     * message instead of a raw constraint violation. The message names the offending product's id and
     * name — without them nobody can tell which row to fix.</p>
     *
     * <p>🔴 The scan deliberately includes soft-deleted rows, exactly like the database key does, and
     * that stays correct because {@code deleteProduct} blanks the barcode on the way out: a hidden row
     * owns no barcode any more, so app and key always give the same verdict. Do NOT "fix" this by
     * filtering on {@code active} — the key has no such filter (MySQL has no partial unique index), so
     * the app would start accepting rows the database then rejects with an unreadable 500. Legacy rows
     * deleted before changeset 099 are the one case where a hidden row still answers here, and the
     * message names it so an operator can clear it.</p>
     *
     * <p>⚠️ No format validation here (check digit, length): the migrated data contains barcodes that fail
     * a check-digit test, and rejecting them would make those products uneditable.</p>
     *
     * @param barcodeId normalised barcode; {@code null} means "no barcode" and is always allowed
     * @param selfId    product being updated, excluded from the check; {@code null} when creating
     */
    private void assertBarcodeFree(String barcodeId, Long selfId) {
        if (barcodeId == null) {
            return;
        }
        for (Product other : productRepository.findAllByBarcodeId(barcodeId)) {
            if (!other.getId().equals(selfId)) {
                throw new BusinessException(
                        "이 바코드를 이미 가진 물품이 있습니다: " + other.getId() + " " + other.getProductName(),
                        HttpStatus.CONFLICT);
            }
        }
    }

    /**
     * Validate that price is positive (> 0)
     *
     * @param price the price to validate
     * @throws IllegalArgumentException if price <= 0
     */
    private void validatePrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
    }

    /**
     * Validate netContentUnit with its netContent dependency
     * If netContent is provided, netContentUnit becomes required
     *
     * @param netContentUnit the unit to validate (mass KG/G or volume L/ML)
     * @param netContent the net content value
     * @throws IllegalArgumentException if validation fails
     */
    private void validateNetContentUnit(String netContentUnit, String netContent) {
        // If netContent is provided, netContentUnit is required
        if (netContent != null && !netContent.trim().isEmpty()) {
            if (netContentUnit == null || netContentUnit.trim().isEmpty()) {
                throw new IllegalArgumentException("netContentUnit is required when netContent is provided");
            }
        }

        // If netContentUnit is provided, validate it
        if (netContentUnit != null && !netContentUnit.trim().isEmpty()) {
            boolean isValid = false;
            for (String validUnit : VALID_NET_CONTENT_UNITS) {
                if (validUnit.equals(netContentUnit)) {
                    isValid = true;
                    break;
                }
            }
            if (!isValid) {
                throw new IllegalArgumentException("netContentUnit must be one of: KG, G, L, ML");
            }
        }
    }

    /**
     * Map Product entity to ProductResponse DTO
     *
     * @param product the product entity to map
     * @return ProductResponse DTO
     */
    private ProductResponse mapToResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .price(product.getPrice())
                .productName(product.getProductName())
                .store(product.getStore())
                .netContentUnit(product.getNetContentUnit())
                .packageHeight(product.getPackageHeight())
                .packageLength(product.getPackageLength())
                .packageWidth(product.getPackageWidth())
                .netContent(product.getNetContent())
                .description(product.getDescription())
                .imageUrl(product.getImageUrl())
                .active(product.getActive())
                .createdDate(product.getCreatedAt())
                .modifiedDate(product.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        // Find product by ID
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // Check if product is active (already soft-deleted products cannot be deleted again)
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // Deletion guard (FEATURE_2609_69 / A): a product still composing a master or a channel listing
        // option may not be deleted. Since this is a soft delete no FK would fire — those rows would just
        // keep pointing at a hidden product. The links are never migrated automatically (PLAN D6-a), so the
        // operator unlinks them first. 🔴 The merge flow (03) uses this same path: no guard-free variant.
        ProductUsageResponse usage = productUsageService.getUsage(id);
        if (!usage.deletable()) {
            throw new ProductInUseException(usage.blockers());
        }

        // 🔴 Releasing the barcode is part of the delete, not an extra.
        // The row survives a soft delete with every column intact, and uq_products_tenant_barcode
        // (changeset 098) counts hidden rows too — MySQL has no partial unique index. So a barcode left
        // on a deleted product stays reserved forever and can never be given to the product that
        // replaces it, which is exactly what the user hits while cleaning up duplicates. Blanking it
        // here keeps the application guard (assertBarcodeFree, which also scans inactive rows) and the
        // database key in agreement: no hidden row owns a barcode, so both answer the same way.
        // Same move as ProductMergeService.releaseSourceBarcode, applied to the ordinary delete.
        String releasedBarcode = product.getBarcodeId();

        // Soft delete using immutable pattern with Builder - use toBuilder to preserve audit fields
        Product deletedProduct = product.toBuilder()
                .active(false)
                .barcodeId(null)
                .build();

        // Save updated product
        productRepository.save(deletedProduct);

        // ⚠️ The barcode is gone from the row: there is no restore endpoint, so this log line is the only
        // trace left of which code the product used to carry.
        if (releasedBarcode != null) {
            log.info("Released barcode {} from soft-deleted product {}", releasedBarcode, id);
        }
    }
}
