package com.pms.config;

import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 로컬 시드 데이터 — {@code @Profile("local")} 에서만 동작.
 *
 * 로그인·목록 화면이 offline(라이브 마켓 호출 0)에서 즉시 동작하도록 최소 데이터를 채운다:
 * <ul>
 *   <li>ADMIN 1 + USER 1 (이메일 로그인, 비번은 BCrypt 인코딩)</li>
 *   <li>Seller 1 + MarketplaceAccount 1 (더미 자격증명 — secretKey 암호화 저장 경로 검증용)</li>
 *   <li>Product 3 (썸네일 없음)</li>
 *   <li>ProductListing 2 (판매상품 — 각각 옵션 1 + 옵션당 Product 연결, 목록/상세 화면 검증용)</li>
 *   <li>기준 데이터: 택배사 2 + 택배비 2(CJ 3000 기본), 박스 3(중박스 기본), 카테고리 4(COUPANG/NAVER),
 *       수수료 기본 2(COUPANG 10% / NAVER 6%), 마진 프리셋 2(판매자 × COUPANG 15% / NAVER 12%)
 *       — 채널추가·상품등록 가격 역산이 offline 에서 바로 동작</li>
 * </ul>
 *
 * <p><b>멱등</b>: 각 영역은 {@code count() > 0} 이면 skip 하므로 재시작 시 중복 생성되지 않는다.
 *
 * <p>로컬 로그인 계정:
 * <ul>
 *   <li>admin@oclyx.local / admin1234 (ADMIN)</li>
 *   <li>user@oclyx.local / user1234 (USER)</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductRepository productRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final CarrierRepository carrierRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PackageRepository packageRepository;
    private final CategoryRepository categoryRepository;
    private final CommissionRateRepository commissionRateRepository;
    private final MarginPolicyRepository marginPolicyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Startup runner has no SecurityContext → TenantContext is empty (NO_TENANT). Set tenant 1
        // explicitly so @TenantId entities (Seller/MarketplaceAccount/Product/ProductListing) seed
        // under tenant 1 instead of failing the tenant FK. Clear afterwards (thread-pool hygiene).
        TenantContext.set(1L);
        try {
            seedUsers();
            Seller seller = seedSellerAndAccount();
            List<Product> products = seedProducts();
            seedProductListings(seller, products);
            // 기준 데이터(택배사·택배비·박스·카테고리·수수료·마진 프리셋) — 채널추가/가격엔진이 offline 에서 바로 동작하도록.
            seedCarriersAndRates();
            seedPackages();
            seedCategories();
            seedCommissionRates();
            seedMarginPolicies(seller);
        } finally {
            TenantContext.clear();
        }
    }

    private void seedUsers() {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.save(User.builder()
                .email("admin@oclyx.local")
                .password(passwordEncoder.encode("admin1234"))
                .name("로컬 관리자")
                .role(Role.ADMIN)
                .build());
        userRepository.save(User.builder()
                .email("user@oclyx.local")
                .password(passwordEncoder.encode("user1234"))
                .name("로컬 사용자")
                .role(Role.USER)
                .build());
        log.info("[LOCAL-SEED] users seeded (admin@oclyx.local / user@oclyx.local)");
    }

    private Seller seedSellerAndAccount() {
        if (sellerRepository.count() > 0) {
            return sellerRepository.findAll().get(0);
        }
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("로컬 테스트 판매자")
                .businessRegistration("000-00-00000")
                .build());
        // 더미 자격증명 — secretKey 는 AesAttributeConverter 로 암호화되어 저장(암호화 경로 검증).
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller)
                .platform("COUPANG")
                .accountAlias("로컬 더미 쿠팡 계정")
                .vendorId("A00000000")
                .accessKey("local-dummy-access-key")
                .secretKey("local-dummy-secret-key")
                .isActive(true)
                .build());
        log.info("[LOCAL-SEED] seller + marketplace account seeded");
        return seller;
    }

    private List<Product> seedProducts() {
        if (productRepository.count() > 0) {
            return productRepository.findAll();
        }
        List<Product> products = productRepository.saveAll(List.of(
                localProduct("로컬 상품 A", "브랜드A", "1000"),
                localProduct("로컬 상품 B", "브랜드B", "2000"),
                localProduct("로컬 상품 C", "브랜드C", "3000")));
        log.info("[LOCAL-SEED] 3 products seeded");
        return products;
    }

    /**
     * 판매상품(ProductListing) 시드 — 목록(GET /api/product-listings?platform=COUPANG)과
     * 상세/수정 화면이 offline 에서 동작하도록 옵션·연결상품까지 채운다.
     *
     * <ul>
     *   <li>판매상품 A: 단일 옵션 + Product A 1개</li>
     *   <li>판매상품 B: 묶음 옵션 + Product B 1 / Product C 2 (다중 상품 매핑 검증)</li>
     * </ul>
     *
     * category / delivery / package_ 는 nullable 이므로 생략(로컬 최소 데이터).
     */
    private void seedProductListings(Seller seller, List<Product> products) {
        if (productListingRepository.count() > 0 || products.size() < 3) {
            return;
        }
        // 판매상품 A: 단일 옵션 + Product A
        ProductListing listingA = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG")
                .platformProductId("LOCAL-0001")
                .name("로컬 판매상품 A")
                .seller(seller)
                .build());
        ProductListingOption optionA = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listingA)
                .optionName("기본 옵션")
                .sellingPrice(new BigDecimal("15000"))
                .platformOptionId("LOCAL-OPT-0001")
                .build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(optionA)
                .product(products.get(0))
                .quantity(1)
                .build());

        // 판매상품 B: 묶음 옵션 + Product B 1 / Product C 2
        ProductListing listingB = productListingRepository.save(ProductListing.builder()
                .platform("COUPANG")
                .platformProductId("LOCAL-0002")
                .name("로컬 판매상품 B (2종 묶음)")
                .seller(seller)
                .build());
        ProductListingOption optionB = productListingOptionRepository.save(ProductListingOption.builder()
                .productListing(listingB)
                .optionName("묶음 옵션")
                .sellingPrice(new BigDecimal("32000"))
                .platformOptionId("LOCAL-OPT-0002")
                .build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(optionB)
                .product(products.get(1))
                .quantity(1)
                .build());
        productListingProductRepository.save(ProductListingProduct.builder()
                .productListingOption(optionB)
                .product(products.get(2))
                .quantity(2)
                .build());
        log.info("[LOCAL-SEED] 2 product listings seeded (platform=COUPANG)");
    }

    /**
     * 택배사(Carrier) 2 + 택배비(CarrierRate) 2 시드. CJ STANDARD 3000원을 기본(isDefault)으로 둔다
     * (기본 유니크는 서비스가 강제하나, 시더는 하나만 기본으로 직접 세팅).
     */
    private void seedCarriersAndRates() {
        if (carrierRepository.count() > 0) {
            return;
        }
        Carrier cj = carrierRepository.save(Carrier.builder().name("CJ대한통운").isActive(true).build());
        Carrier post = carrierRepository.save(Carrier.builder().name("우체국택배").isActive(true).build());
        carrierRateRepository.save(CarrierRate.builder()
                .carrier(cj).type("STANDARD").cost(new BigDecimal("3000"))
                .effectiveDate(LocalDate.now()).isDefault(true).build());
        carrierRateRepository.save(CarrierRate.builder()
                .carrier(post).type("STANDARD").cost(new BigDecimal("3500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        log.info("[LOCAL-SEED] 2 carriers + 2 carrier rates seeded (CJ 3000 default)");
    }

    /** 박스(Package) 3 시드 — 소/중/대. 중(M)을 기본으로 둔다. */
    private void seedPackages() {
        if (packageRepository.count() > 0) {
            return;
        }
        packageRepository.save(localPackage("소박스", "300", false));
        packageRepository.save(localPackage("중박스", "500", true));
        packageRepository.save(localPackage("대박스", "800", false));
        log.info("[LOCAL-SEED] 3 packages seeded (중박스 default)");
    }

    /** 카테고리(Category) 시드 — COUPANG/NAVER 각각 몇 개(수수료·상품등록 카테고리 선택용). */
    private void seedCategories() {
        if (categoryRepository.count() > 0) {
            return;
        }
        categoryRepository.saveAll(List.of(
                localCategory("패션의류", "COUPANG", "C-1001"),
                localCategory("생활용품", "COUPANG", "C-1002"),
                localCategory("패션의류", "NAVER", "N-2001"),
                localCategory("생활용품", "NAVER", "N-2002")));
        log.info("[LOCAL-SEED] 4 categories seeded (COUPANG/NAVER)");
    }

    /**
     * 수수료율(CommissionRate) 플랫폼 기본값 시드 — 가격엔진 폴백(category 없이 platform 기본).
     * COUPANG 10%, NAVER 6%.
     */
    private void seedCommissionRates() {
        if (commissionRateRepository.count() > 0) {
            return;
        }
        commissionRateRepository.save(CommissionRate.builder()
                .platform("COUPANG").category(null).rate(new BigDecimal("0.1000")).isDefault(true).build());
        commissionRateRepository.save(CommissionRate.builder()
                .platform("NAVER").category(null).rate(new BigDecimal("0.0600")).isDefault(true).build());
        log.info("[LOCAL-SEED] 2 default commission rates seeded (COUPANG 10% / NAVER 6%)");
    }

    /**
     * 마진 프리셋(MarginPolicy) 시드 — 시드 판매자 × (COUPANG/NAVER). 상품등록 가격 역산이 offline 에서
     * 성공하려면 (판매자, 플랫폼) 마진율이 있어야 한다. COUPANG 15%, NAVER 12%.
     */
    private void seedMarginPolicies(Seller seller) {
        if (marginPolicyRepository.count() > 0) {
            return;
        }
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("NAVER").marginRate(new BigDecimal("0.1200")).build());
        log.info("[LOCAL-SEED] 2 margin policies seeded (seller × COUPANG 15% / NAVER 12%)");
    }

    private Package localPackage(String type, String cost, boolean isDefault) {
        return Package.builder()
                .type(type)
                .cost(new BigDecimal(cost))
                .effectiveDate(LocalDate.now())
                .isDefault(isDefault)
                .build();
    }

    private Category localCategory(String name, String platform, String platformCategoryId) {
        return Category.builder()
                .name(name)
                .platform(platform)
                .platformCategoryId(platformCategoryId)
                .build();
    }

    private Product localProduct(String productName, String brand, String price) {
        return Product.builder()
                .productName(productName)
                .brand(brand)
                .price(new BigDecimal(price))
                .active(true)
                .build();
    }
}
