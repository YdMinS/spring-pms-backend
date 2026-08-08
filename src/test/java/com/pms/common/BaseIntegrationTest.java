package com.pms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.domain.Package;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    protected static final String USER_EMAIL = "user@test.com";
    protected static final String USER_PASSWORD = "testpass123";
    protected static final String ADMIN_EMAIL = "admin@test.com";
    protected static final String ADMIN_PASSWORD = "testpass123";

    protected String adminToken;
    protected String userToken;

    /** Real DB id of the carrier rate seeded in setUp (H2 IDENTITY is not deterministic across tests). */
    protected Long seededCarrierRateId;

    /** Real DB id of the package seeded in setUp (H2 IDENTITY climbs across tests; do not hardcode 1). */
    protected Long seededPackageId;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected CarrierRateRepository carrierRateRepository;

    @Autowired
    protected CarrierRepository carrierRepository;

    @Autowired
    protected PackageRepository packageRepository;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected BCryptPasswordEncoder passwordEncoder;

    protected void registerTestUsers() {
        User admin = User.builder()
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .name("Admin User")
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);

        User user = User.builder()
                .email(USER_EMAIL)
                .password(passwordEncoder.encode(USER_PASSWORD))
                .name("Test User")
                .role(Role.USER)
                .build();
        userRepository.save(user);
    }

    protected String generateAdminToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    protected String generateUserToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + USER_EMAIL + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    protected void registerTestCarrierRates() {
        // Carrier master must exist first (carrier_rate.carrier is now an FK).
        Carrier carrier = carrierRepository.save(
                Carrier.builder().name("DHL").isActive(true).build());
        // Seed a carrier rate; capture the real id (H2 IDENTITY climbs across tests).
        CarrierRate carrierRate = CarrierRate.builder()
                .carrier(carrier)
                .type("EXPRESS")
                .cost(new BigDecimal("15.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();
        seededCarrierRateId = carrierRateRepository.saveAndFlush(carrierRate).getId();
    }

    protected void registerTestPackages() {
        // Seed a default test package; capture the real id.
        // IDENTITY ignores any provided id and deleteAll does not reset the counter,
        // so the id is not deterministic across tests — tests must use seededPackageId.
        Package pkg = Package.builder()
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();
        seededPackageId = packageRepository.saveAndFlush(pkg).getId();
    }

    protected void cleanupTestData() {
        // JPA deleteAll (not native SQL) so it stays consistent with the persistence context:
        // a native delete would strand managed @TenantId entities (package/carrier_rate) that a
        // later autoflush then fails to update (stale-object). These tests are @Transactional, so
        // rollback already wipes every tenant's rows — the tenant filter leaving residue is a
        // non-issue here; setUpBase pins the tenant to 1 so deleteAll targets the seeded rows.
        // Delete refresh tokens first: they FK-reference member rows.
        refreshTokenRepository.deleteAll();
        packageRepository.deleteAll();
        carrierRateRepository.deleteAll();
        carrierRepository.deleteAll();           // after rates: FK dependency
        userRepository.deleteByEmail(ADMIN_EMAIL);
        userRepository.deleteByEmail(USER_EMAIL);
    }

    @BeforeEach
    public void setUpBase() throws Exception {
        // Seed under tenant 1 so @TenantId entities (carrier_rate, package) get tenant_id=1,
        // matching the tenant claim of the seeded admin/user (User.tenantId default = 1L). Without
        // this, seeding runs with no context (NO_TENANT) and the tenant-1 API requests would not
        // see the seeded rows. See tenant/ProductTenantIsolationTest for the isolation mechanism.
        TenantContext.set(1L);
        registerTestUsers();
        registerTestCarrierRates();
        registerTestPackages();
        adminToken = generateAdminToken();
        userToken = generateUserToken();
    }

    @AfterEach
    public void tearDownBase() {
        cleanupTestData();
        TenantContext.clear();
    }
}
