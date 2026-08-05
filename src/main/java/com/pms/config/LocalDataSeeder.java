package com.pms.config;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.Seller;
import com.pms.domain.User;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 로컬 시드 데이터 — {@code @Profile("local")} 에서만 동작.
 *
 * 로그인·목록 화면이 offline(라이브 마켓 호출 0)에서 즉시 동작하도록 최소 데이터를 채운다:
 * <ul>
 *   <li>ADMIN 1 + USER 1 (이메일 로그인, 비번은 BCrypt 인코딩)</li>
 *   <li>Seller 1 + MarketplaceAccount 1 (더미 자격증명 — secretKey 암호화 저장 경로 검증용)</li>
 *   <li>Product 3 (썸네일 없음)</li>
 * </ul>
 *
 * <p><b>멱등</b>: 각 영역은 {@code count() > 0} 이면 skip 하므로 재시작 시 중복 생성되지 않는다.
 *
 * <p>로컬 로그인 계정:
 * <ul>
 *   <li>admin@oklyx.local / admin1234 (ADMIN)</li>
 *   <li>user@oklyx.local / user1234 (USER)</li>
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
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        seedUsers();
        seedSellerAndAccount();
        seedProducts();
    }

    private void seedUsers() {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.save(User.builder()
                .email("admin@oklyx.local")
                .password(passwordEncoder.encode("admin1234"))
                .name("로컬 관리자")
                .role(Role.ADMIN)
                .build());
        userRepository.save(User.builder()
                .email("user@oklyx.local")
                .password(passwordEncoder.encode("user1234"))
                .name("로컬 사용자")
                .role(Role.USER)
                .build());
        log.info("[LOCAL-SEED] users seeded (admin@oklyx.local / user@oklyx.local)");
    }

    private void seedSellerAndAccount() {
        if (sellerRepository.count() > 0) {
            return;
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
    }

    private void seedProducts() {
        if (productRepository.count() > 0) {
            return;
        }
        productRepository.saveAll(List.of(
                localProduct("로컬 상품 A", "브랜드A", "1000"),
                localProduct("로컬 상품 B", "브랜드B", "2000"),
                localProduct("로컬 상품 C", "브랜드C", "3000")));
        log.info("[LOCAL-SEED] 3 products seeded");
    }

    private Product localProduct(String productName, String brand, String price) {
        return Product.builder()
                .name(productName)
                .productName(productName)
                .brand(brand)
                .price(new BigDecimal(price))
                .active(true)
                .build();
    }
}
