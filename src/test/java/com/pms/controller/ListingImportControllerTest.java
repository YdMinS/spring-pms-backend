package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.CommissionRate;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductListingTagRevisionRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailRenderer;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가져오기 엔드포인트 2개(FEATURE_2609_22 / 02): 권한(401/403) + 미리보기·커밋 happy path + 요청 검증.
 *
 * <p>⚠️ 의도적으로 {@code @Transactional} 이 아니다 — 형제 엔드포인트({@code ChannelAddControllerTest})와 같은
 * 이유이고, 커밋된 시드를 쓰는 편이 시작 시더의 테넌트 1 기본 템플릿과도 맞는다. {@link CoupangApiClient} 는
 * 목이라 라이브 호출이 없다(가져오기는 어차피 읽기 1회뿐이다).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListingImportControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CommissionRateRepository commissionRateRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private MarginPolicyRepository marginPolicyRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private MasterProductComponentRepository masterProductComponentRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private ProductListingTagRevisionRepository productListingTagRevisionRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private CarrierRateRepository carrierRateRepository;
    @Autowired private PackageRepository packageRepository;

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;
    @MockBean private CoupangApiClient coupangApiClient;

    private static final String BASE = "/api/admin/master-products";
    private static final String ADMIN_EMAIL = "admin@listingimport.com";
    private static final String USER_EMAIL = "user@listingimport.com";
    private static final String PASSWORD = "testpass123";
    private static final String PRODUCT_ID = "222333444";

    /**
     * 한 옵션짜리 쿠팡 조회 응답 — 상품명·카테고리·태그·가격·재고까지 실제 응답 모양 그대로.
     * ⚠️ {@code displayCategoryCode} 는 <b>매핑에 없는</b> 코드다: D15 경고 문구가 실제로 응답까지 실려
     * 나가는지가 이 테스트의 핵심이고, 매핑되는 코드를 쓰면 필드가 null 이 되어 직렬화에서 사라진다.
     */
    private static final String COUPANG_PRODUCT =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":222333444,"
                    + "\"sellerProductName\":\"운동화 6입\",\"displayCategoryCode\":\"99999\","
                    + "\"statusName\":\"승인완료\",\"items\":[{\"itemName\":\"6입\",\"vendorItemId\":8123,"
                    + "\"sellerProductItemId\":9123,\"salePrice\":12900,\"originalPrice\":15900,"
                    + "\"maximumBuyCount\":50,\"searchTags\":[\"운동화\"]}]}}";

    private String adminToken;
    private String userToken;
    private Long masterId;
    private Long sellerId;
    private Long productId;

    @BeforeEach
    void seed() throws Exception {
        TenantContext.set(1L);

        userRepository.save(User.builder().email(ADMIN_EMAIL).password(passwordEncoder.encode(PASSWORD))
                .name("Admin").role(Role.ADMIN).build());
        userRepository.save(User.builder().email(USER_EMAIL).password(passwordEncoder.encode(PASSWORD))
                .name("User").role(Role.USER).build());

        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build());
        sellerId = seller.getId();
        Product product = productRepository.save(Product.builder()
                .productName("운동화").brand("나이키")
                .price(new BigDecimal("1500")).imageUrl("products/p.jpg").active(true).build());
        productId = product.getId();
        Category category = categoryRepository.save(Category.builder().name("신발").build());

        commissionRateRepository.save(CommissionRate.builder()
                .platform(Platform.COUPANG).category(null).rate(new BigDecimal("0.10")).isDefault(true).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform(Platform.COUPANG).marginRate(new BigDecimal("0.1500")).build());

        Carrier carrier = carrierRepository.save(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.save(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = packageRepository.save(Package.builder()
                .type("M").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());

        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).category(category)
                .defaultDelivery(delivery).defaultPackage(box).build());
        masterId = master.getId();
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());
        // The master's component set = the quantity input rows the import asks the user to fill (D9).
        masterProductComponentRepository.save(MasterProductComponent.builder()
                .masterProduct(master).product(product).build());
        // An existing option whose BOM equals the request's composition → the import links it (D10).
        MasterProductOption option = masterProductOptionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name("1세트").build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(product).quantity(1).build());
        // The import resolves the account by (seller, platform) — it has no cell to read it from yet.
        marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform(Platform.COUPANG).accountAlias("메인")
                .vendorId("V1").vendorUserId("wing-user")
                .accessKey("ak").secretKey("sk").isActive(true).build());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{4, 5, 6});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");
        // ⚠️ Import is read-only against the market: only GET is ever stubbed here.
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(COUPANG_PRODUCT);

        adminToken = login(ADMIN_EMAIL);
        userToken = login(USER_EMAIL);
    }

    @AfterEach
    void cleanup() {
        TenantContext.set(1L);
        refreshTokenRepository.deleteAll();
        generatedProductDataRepository.deleteAll();
        productListingTagRevisionRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        marketplaceAccountRepository.deleteAll();
        categoryMappingRepository.deleteAll();
        platformCategoryRepository.deleteAll();
        masterProductOptionItemRepository.deleteAll();
        masterProductOptionRepository.deleteAll();
        masterProductComponentRepository.deleteAll();
        masterProductRepository.deleteAll();
        marginPolicyRepository.deleteAll();
        commissionRateRepository.deleteAll();
        categoryRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        carrierRateRepository.deleteAll();
        carrierRepository.deleteAll();
        packageRepository.deleteAll();
        userRepository.deleteAll();
        TenantContext.clear();
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    private String previewBody() throws Exception {
        return objectMapper.writeValueAsString(ListingImportPreviewRequest.builder()
                .sellerId(sellerId).platform("COUPANG").platformProductId(PRODUCT_ID).build());
    }

    private String importBody() throws Exception {
        return objectMapper.writeValueAsString(ListingImportRequest.builder()
                .sellerId(sellerId).platform("COUPANG").platformProductId(PRODUCT_ID)
                .options(List.of(ListingImportRequest.OptionSpec.builder()
                        .vendorItemId("8123").itemName("6입").masterOptionName("6입")
                        .components(List.of(ListingImportRequest.Component.builder()
                                .productId(productId).quantity(1).build()))
                        .build()))
                .build());
    }

    private String previewPath() {
        return BASE + "/" + masterId + "/listings/import/preview";
    }

    private String importPath() {
        return BASE + "/" + masterId + "/listings/import";
    }

    // ---- happy path ----

    @Test
    void testPreview200() throws Exception {
        mockMvc.perform(post(previewPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(previewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productName").value("운동화 6입"))
                .andExpect(jsonPath("$.data.options.length()").value(1))
                .andExpect(jsonPath("$.data.components.length()").value(1))
                .andExpect(jsonPath("$.data.categoryMatched").value(false))
                // D15: the exact wording must reach the client (it renders it verbatim).
                .andExpect(jsonPath("$.data.categoryWarning").exists())
                .andExpect(jsonPath("$.data.categoryWarning").value(org.hamcrest.Matchers.startsWith(
                        "정확한 카테고리 매핑이 안되어")));
    }

    @Test
    void testImport200() throws Exception {
        mockMvc.perform(post(importPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(importBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productListingId").isNumber())
                // D19: the product is already live on the market — not a DRAFT.
                .andExpect(jsonPath("$.data.status").value("SELLING"));
    }

    // ---- request validation ----

    @Test
    void testImportInvalidBody400() throws Exception {
        String body = objectMapper.writeValueAsString(ListingImportRequest.builder()
                .sellerId(sellerId).platform("COUPANG").platformProductId(PRODUCT_ID)
                .options(List.of()).build());

        mockMvc.perform(post(importPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testImportUnauthorized401() throws Exception {
        mockMvc.perform(post(importPath())
                        .contentType("application/json").content(importBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testPreviewForbidden403() throws Exception {
        mockMvc.perform(post(previewPath())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(previewBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testImportForbidden403() throws Exception {
        mockMvc.perform(post(importPath())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(importBody()))
                .andExpect(status().isForbidden());
    }
}
