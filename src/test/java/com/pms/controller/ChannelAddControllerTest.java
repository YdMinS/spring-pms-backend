package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.domain.PlatformCategory;
import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProduct;
import com.pms.domain.CategoryMapping;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import com.pms.service.ImageStorageService;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailRenderer;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Single channel-add endpoint (FEATURE_2608_06 / 15): authority (401/403/201) + generated price on the new
 * DRAFT cell, and the duplicate-channel 409.
 *
 * <p>⚠️ Intentionally NOT {@code @Transactional}: {@code addChannel} now runs {@code REQUIRES_NEW}, which
 * opens a separate transaction/connection that cannot see a rolling-back test transaction's uncommitted
 * seeds. Seeds are therefore committed (non-transactional) and cleaned up with tenant-scoped {@code deleteAll}
 * in {@code @AfterEach}. Renderer / storage / image-loader are mocked so the reused seam runs without
 * disk/network. The tenant-1 default thumbnail template is provided by the startup seeder (visible here
 * because each op reads the live {@link TenantContext}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelAddControllerTest {

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
    @Autowired private MasterProductOptionRepository masterProductOptionRepository;
    @Autowired private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Autowired private ProductListingRepository productListingRepository;
    @Autowired private ProductListingOptionRepository productListingOptionRepository;
    @Autowired private ProductListingProductRepository productListingProductRepository;
    @Autowired private GeneratedProductDataRepository generatedProductDataRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private CarrierRateRepository carrierRateRepository;
    @Autowired private PackageRepository packageRepository;

    @MockBean private ThumbnailRenderer thumbnailRenderer;
    @MockBean private ImageStorageService imageStorageService;
    @MockBean private ProductImageLoader productImageLoader;

    private static final String BASE = "/api/admin/master-products";
    private static final String ADMIN_EMAIL = "admin@channeladd.com";
    private static final String USER_EMAIL = "user@channeladd.com";
    private static final String PASSWORD = "testpass123";

    private String adminToken;
    private String userToken;
    private Long masterId;
    private Long sellerId;

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
        Category category = categoryRepository.save(Category.builder().name("신발").build());

        // Commission (COUPANG default) + margin preset so the reused price engine resolves.
        commissionRateRepository.save(CommissionRate.builder()
                .platform("COUPANG").category(null).rate(new BigDecimal("0.10")).isDefault(true).build());
        marginPolicyRepository.save(MarginPolicy.builder()
                .seller(seller).platform("COUPANG").marginRate(new BigDecimal("0.1500")).build());

        // (1500 + 2500 + 500) / 0.75 = 6000.
        Carrier carrier = carrierRepository.save(Carrier.builder().name("CJ").isActive(true).build());
        CarrierRate delivery = carrierRateRepository.save(CarrierRate.builder()
                .carrier(carrier).type("STANDARD").cost(new BigDecimal("2500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());
        Package box = packageRepository.save(Package.builder()
                .type("M").cost(new BigDecimal("500"))
                .effectiveDate(LocalDate.now()).isDefault(false).build());

        // Channel config lives on the master: default delivery/box + a single standard category (44); the
        // COUPANG code comes from a CategoryMapping (channel-add pre-validation needs the mapping to exist).
        MasterProduct master = masterProductRepository.save(MasterProduct.builder()
                .name("운동화 마스터").active(true).category(category)
                .defaultDelivery(delivery).defaultPackage(box).build());
        masterId = master.getId();
        PlatformCategory platformCategory = platformCategoryRepository.save(PlatformCategory.builder()
                .platform("COUPANG").code("cat-1").name("운동화")
                .commissionRate(new BigDecimal("0.10")).build());
        categoryMappingRepository.save(CategoryMapping.builder()
                .category(category).platform("COUPANG").platformCategoryId("cat-1")
                .platformCategory(platformCategory).build());
        MasterProductOption option = masterProductOptionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name("1세트").build());
        masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(product).quantity(1).build());

        given(productImageLoader.load(any())).willReturn(new byte[]{1, 2, 3});
        given(thumbnailRenderer.render(any(), any(), any())).willReturn(new byte[]{4, 5, 6});
        given(imageStorageService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .willReturn("thumbnails/generated.jpg");

        adminToken = login(ADMIN_EMAIL);
        userToken = login(USER_EMAIL);
    }

    @AfterEach
    void cleanup() {
        // Non-transactional: REQUIRES_NEW committed real rows. Delete child → parent under tenant 1.
        TenantContext.set(1L);
        refreshTokenRepository.deleteAll();
        generatedProductDataRepository.deleteAll();
        productListingProductRepository.deleteAll();
        productListingOptionRepository.deleteAll();
        productListingRepository.deleteAll();
        categoryMappingRepository.deleteAll();
        platformCategoryRepository.deleteAll();
        masterProductOptionItemRepository.deleteAll();
        masterProductOptionRepository.deleteAll();
        masterProductRepository.deleteAll();
        marginPolicyRepository.deleteAll();
        commissionRateRepository.deleteAll();
        categoryRepository.deleteAll();
        productRepository.deleteAll();
        sellerRepository.deleteAll();
        carrierRateRepository.deleteAll();
        carrierRepository.deleteAll();
        packageRepository.deleteAll();
        // deleteAll wraps its own transaction (SimpleJpaRepository); the custom @Modifying deleteByEmail would
        // need an ambient one, which this non-transactional test does not have.
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

    private String body() throws Exception {
        return objectMapper.writeValueAsString(ChannelAddRequest.builder()
                .sellerId(sellerId).platform("COUPANG").build());
    }

    // ---- authority (MUST-KEEP) ----

    @Test
    void addChannel_noToken_returns401() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addChannel_userToken_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void addChannel_adminToken_returns201WithDraftAndPrice() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.productListingId").isNumber())
                .andExpect(jsonPath("$.data.generated.optionPrices.length()").value(1))
                // (1500 + 2500 + 500) / 0.75 = 6000, rounded to nearest 10 won
                .andExpect(jsonPath("$.data.generated.optionPrices[0].sellingPrice").value(6000.00));
    }

    // ---- duplicate channel (409) ----

    @Test
    void addChannel_duplicateAccount_returns409() throws Exception {
        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/" + masterId + "/listings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
