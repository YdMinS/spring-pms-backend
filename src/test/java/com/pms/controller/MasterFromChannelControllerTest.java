package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Category;
import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PlatformCategory;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.request.MasterFromChannelRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.service.ImageStorageService;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.listing.category.CoupangCategoryMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마켓 상품으로 마스터 만들기 엔드포인트 2개(FEATURE_2609_45 / 01): 권한(401/403) + 미리보기·생성 happy path
 * + 요청 검증.
 *
 * <p>⚠️ 의도적으로 {@code @Transactional} 이 아니다 — 형제 엔드포인트({@code ListingImportControllerTest})와
 * 같은 이유다. {@link CoupangApiClient} 는 목이라 라이브 호출이 없다(이 기능은 읽기 GET 2회뿐이다).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterFromChannelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private CategoryMappingRepository categoryMappingRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private CoupangAccountCredentialRepository credentialRepository;
    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private MasterProductComponentRepository masterProductComponentRepository;
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private PriceChangeLogRepository priceChangeLogRepository;
    @Autowired private ImageStorageService imageStorageService;

    @MockBean private CoupangApiClient coupangApiClient;

    private static final String BASE = "/api/admin/master-products";
    private static final String ADMIN_EMAIL = "admin@masterfromchannel.com";
    private static final String USER_EMAIL = "user@masterfromchannel.com";
    private static final String PASSWORD = "testpass123";
    private static final String PRODUCT_ID = "222333444";

    /** 옵션 2건 + 옵션마다 다른 속성(수량) + 고시. {@code displayCategoryCode} 는 시드한 매핑과 일치한다. */
    private static final String COUPANG_PRODUCT =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":222333444,"
                    + "\"sellerProductName\":\"운동화 세트\",\"displayCategoryCode\":\"cat-1\","
                    + "\"statusName\":\"승인완료\",\"items\":["
                    + "{\"itemName\":\"6입\",\"vendorItemId\":8123,\"sellerProductItemId\":9123,"
                    + "\"salePrice\":12900,\"originalPrice\":15900,\"maximumBuyCount\":85,"
                    + "\"searchTags\":[\"운동화\"],"
                    + "\"attributes\":[{\"attributeTypeName\":\"최소 중량\",\"attributeValueName\":\"36.9g\"},"
                    + "{\"attributeTypeName\":\"식품 프리미엄\",\"attributeValueName\":\"해당없음\"}],"
                    + "\"notices\":[{\"noticeCategoryName\":\"가공식품\",\"noticeCategoryDetailName\":\"제품명\","
                    + "\"content\":\"상품 상세페이지 참조\"}]},"
                    + "{\"itemName\":\"12입\",\"vendorItemId\":8124,\"sellerProductItemId\":9124,"
                    + "\"salePrice\":23900,\"originalPrice\":29900,\"maximumBuyCount\":40,"
                    + "\"searchTags\":[\"운동화\"],"
                    + "\"attributes\":[{\"attributeTypeName\":\"최소 중량\",\"attributeValueName\":\"73.8g\"},"
                    + "{\"attributeTypeName\":\"식품 프리미엄\",\"attributeValueName\":\"해당없음\"}],"
                    + "\"notices\":[{\"noticeCategoryName\":\"가공식품\",\"noticeCategoryDetailName\":\"제품명\","
                    + "\"content\":\"상품 상세페이지 참조\"}]}]}}";

    private String adminToken;
    private String userToken;
    private Long sellerId;
    private Long productId;
    private Long categoryId;

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

        // 표준 카테고리 + 쿠팡 매핑: D2 역조회(코드 → 표준 카테고리)와 setCategory 의 매핑 가드를 함께 만족한다.
        Category category = categoryRepository.save(Category.builder().name("신발").build());
        categoryId = category.getId();
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform(Platform.COUPANG).code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform(Platform.COUPANG).platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());

        // 이 기능은 계정을 (판매자, 플랫폼)으로 해석한다 — 아직 셀이 없다.
        MarketplaceAccountFixture.saveCredential(credentialRepository,
                marketplaceAccountRepository.save(MarketplaceAccountFixture.coupangCoreBuilder()
                        .seller(seller).platform(Platform.COUPANG).accountAlias("메인")
                        .isActive(true).build()),
                "V1", "wing-user");

        // ⚠️ 읽기 전용: GET 만 스텁한다(상품 조회 + 단위 해석용 카테고리 메타).
        given(coupangApiClient.get(anyString(), anyString(), any())).willAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return path.contains("category-related-metas")
                    ? CoupangCategoryMeta.META_FIXTURE_JSON : COUPANG_PRODUCT;
        });

        adminToken = login(ADMIN_EMAIL);
        userToken = login(USER_EMAIL);
    }

    @AfterEach
    void cleanup() {
        TenantContext.set(1L);
        refreshTokenRepository.deleteAll();
        // 2609_47: 생성 직후 자동생성이 산출물·가격이력을 남길 수 있다 — 셀보다 먼저 지운다(FK).
        priceChangeLogRepository.deleteAll();
        generatedProductDataRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        credentialRepository.deleteAll();          // FK child first
        marketplaceAccountRepository.deleteAll();
        categoryMappingRepository.deleteAll();
        platformCategoryRepository.deleteAll();
        masterProductOptionItemRepository.deleteAll();
        masterProductOptionRepository.deleteAll();
        masterProductComponentRepository.deleteAll();
        masterProductRepository.deleteAll();
        categoryRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
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
        return objectMapper.writeValueAsString(MasterFromChannelPreviewRequest.builder()
                .sellerId(sellerId).platform("COUPANG").platformProductId(PRODUCT_ID).build());
    }

    private MasterFromChannelRequest.OptionSpec spec(String itemName, String platformOptionId, int quantity) {
        return MasterFromChannelRequest.OptionSpec.builder()
                .itemName(itemName).platformOptionId(platformOptionId)
                .components(List.of(MasterFromChannelRequest.Component.builder()
                        .productId(productId).quantity(quantity).build()))
                .build();
    }

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(MasterFromChannelRequest.builder()
                .sellerId(sellerId).platform("COUPANG").platformProductId(PRODUCT_ID)
                .masterName("운동화 마스터").categoryId(categoryId)
                .componentProductIds(List.of(productId))
                .options(List.of(spec("6입", "8123", 6), spec("12입", "8124", 12)))
                .build());
    }

    private String previewPath() {
        return BASE + "/from-channel/preview";
    }

    private String createPath() {
        return BASE + "/from-channel";
    }

    // ---- happy path ----

    @Test
    void testPreview200() throws Exception {
        mockMvc.perform(post(previewPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(previewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.productName").value("운동화 세트"))
                .andExpect(jsonPath("$.data.suggestedMasterName").value("운동화 세트"))
                .andExpect(jsonPath("$.data.options.length()").value(2))
                // D2: 시드한 매핑으로 역조회가 성공한다.
                .andExpect(jsonPath("$.data.categoryResolved").value(true))
                .andExpect(jsonPath("$.data.suggestedCategoryName").value("신발"))
                // D4-1: 전 옵션이 같은 값을 갖는 키만 공통, 옵션마다 다른 값(최소 중량)은 그 옵션에.
                .andExpect(jsonPath("$.data.commonAttributes.['식품 프리미엄']").value("해당없음"))
                .andExpect(jsonPath("$.data.options[0].attributes.['최소 중량']").value("36.9"))
                .andExpect(jsonPath("$.data.noticeGroup").value("가공식품"));
    }

    @Test
    void testCreate200() throws Exception {
        mockMvc.perform(post(createPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.masterProductId").isNumber())
                .andExpect(jsonPath("$.data.productListingId").isNumber())
                .andExpect(jsonPath("$.data.optionCount").value(2))
                // 이미 마켓에서 팔리는 상품이다 — DRAFT 가 아니다.
                .andExpect(jsonPath("$.data.status").value("SELLING"))
                // 2609_47/D4: 자동생성 성공 여부가 응답에 실린다.
                .andExpect(jsonPath("$.data.assetsGenerated").exists());
    }

    /**
     * 🔴 2609_47/D2: 사진이 하나도 없어 자동생성이 실패해도 마스터·셀은 남는다(400 이 아니다).
     * 픽스처의 구성상품 이미지는 디스크에 없으므로 썸네일 단계에서 실패한다.
     */
    @Test
    void testCreateWithoutPhotoStillCreatesMaster() throws Exception {
        mockMvc.perform(post(createPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetsGenerated").value(false))
                .andExpect(jsonPath("$.data.masterProductId").isNumber())
                .andExpect(jsonPath("$.data.productListingId").isNumber());
    }

    /**
     * 🔴 2609_47/D5 회귀 가드: 자동생성이 <b>실제로 돌아도</b>(assetsGenerated=true) 저장된 옵션 판매가는
     * 쿠팡 실가 그대로다 — 우리 마진 계산가로 덮이지 않는다. 단위 테스트로는 못 잡는다(거기선 자동생성이
     * 가짜 객체라 ③판매가 단계에 도달조차 하지 않는다).
     */
    @Test
    void testCreateKeepsMarketPricesAfterAssetGeneration() throws Exception {
        seedLoadableProductImage();

        String response = mockMvc.perform(post(createPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isOk())
                // 먼저 자동생성이 성공했는지 단언한다 — false 면 ③판매가 단계에 닿지도 못한 채
                // "판매가가 그대로"라 통과하는 가짜 그물이 된다.
                .andExpect(jsonPath("$.data.assetsGenerated").value(true))
                .andReturn().getResponse().getContentAsString();

        Long listingId = objectMapper.readTree(response).get("data").get("productListingId").asLong();
        List<ProductListingOption> options =
                productListingOptionRepository.findByProductListingId(listingId);
        assertThat(options).hasSize(2);
        assertThat(options).filteredOn(o -> o.getOptionName().equals("6입"))
                .allSatisfy(o -> assertThat(o.getSellingPrice()).isEqualByComparingTo("12900"));
        assertThat(options).filteredOn(o -> o.getOptionName().equals("12입"))
                .allSatisfy(o -> assertThat(o.getSellingPrice()).isEqualByComparingTo("23900"));
    }

    /** 첫 구성상품에 실제로 로드되는 이미지를 심는다(테스트 프로파일 저장소 = 로컬 디스크). */
    private void seedLoadableProductImage() throws Exception {
        BufferedImage image = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 60, 60);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);

        String storedPath = imageStorageService.uploadImage(
                new MockMultipartFile("file", "p.jpg", "image/jpeg", out.toByteArray()), productId);
        Product product = productRepository.findById(productId).orElseThrow();
        productRepository.save(product.toBuilder().imageUrl(storedPath).build());
    }

    // ---- request validation ----

    @Test
    void testCreateMissingPlatformProductId400() throws Exception {
        String body = objectMapper.writeValueAsString(MasterFromChannelRequest.builder()
                .sellerId(sellerId).platform("COUPANG")
                .masterName("운동화 마스터").categoryId(categoryId)
                .componentProductIds(List.of(productId))
                .options(List.of(spec("6입", "8123", 6)))
                .build());

        mockMvc.perform(post(createPath())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void testCreateUnauthorized401() throws Exception {
        mockMvc.perform(post(createPath())
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testPreviewForbidden403() throws Exception {
        mockMvc.perform(post(previewPath())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(previewBody()))
                .andExpect(status().isForbidden());
    }
}
