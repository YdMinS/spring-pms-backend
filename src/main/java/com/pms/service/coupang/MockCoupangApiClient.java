package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link CoupangApiClient} 의 로컬 Mock 구현 — 라이브 쿠팡 호출 없이 fixture JSON 을 반환한다.
 *
 * <p><b>프로파일</b>: {@code @Profile("local")}. local 프로파일에서만 활성화되며,
 * 이때 {@link CoupangApiClientImpl}({@code @Profile("!local")}) 는 비활성이라 유일한 빈이 된다.
 *
 * <p><b>분기</b>(path substring):
 * <ul>
 *   <li>{@code ordersheets} 포함 → {@code fixtures/coupang/ordersheets.json}</li>
 *   <li>{@code returnRequests} 포함 → {@code fixtures/coupang/returnRequests.json}</li>
 *   <li>{@code seller-products} 포함(GET) → 승인완료 상태 + 옵션 id 인라인 fixture (3c fetchStatus)</li>
 *   <li>{@code seller-products} 포함(POST) → sellerProductId 인라인 fixture (3c register)</li>
 *   <li>{@code display-categories/} 포함(GET) → 카테고리 트리 자식 fixture (45 browse)</li>
 *   <li>{@code categorization/predict} 포함(POST) → 카테고리 추천 fixture (45 predict)</li>
 *   <li>그 외 → {@code {"code":200,"data":[]}}</li>
 * </ul>
 * 반환 JSON 은 실제 파서(예: CoupangOrderSyncServiceImpl.upsert, CoupangListingAdapter)가 그대로 소화할 수
 * 있는 키 구조여야 한다.
 */
@Component
@Profile("local")
public class MockCoupangApiClient implements CoupangApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockCoupangApiClient.class);

    private static final String ORDERSHEETS_FIXTURE = "fixtures/coupang/ordersheets.json";
    private static final String RETURN_REQUESTS_FIXTURE = "fixtures/coupang/returnRequests.json";
    private static final String EMPTY = "{\"code\":200,\"data\":[]}";

    // 3c fixtures (inline): register → sellerProductId, fetchStatus → 승인완료 + option ids.
    private static final String SELLER_PRODUCT_REGISTER =
            "{\"code\":\"SUCCESS\",\"data\":123456789}";
    private static final String SELLER_PRODUCT_FETCH =
            "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":123456789,\"statusName\":\"승인완료\","
                    + "\"items\":[{\"itemName\":\"1세트\",\"vendorItemId\":987654321,"
                    + "\"sellerProductItemId\":555666777}]}}";

    // 45 category lookup fixtures (inline). Tree = data.child[] (displayCategoryCode/name/child/last):
    // 1001 has a nested child + last=false → non-leaf, 1002 has empty child → leaf.
    private static final String DISPLAY_CATEGORIES_FIXTURE =
            "{\"code\":200,\"data\":{\"displayCategoryCode\":\"0\",\"name\":\"루트\",\"child\":["
                    + "{\"displayCategoryCode\":\"1001\",\"name\":\"패션의류잡화\",\"last\":false,"
                    + "\"child\":[{\"displayCategoryCode\":\"2001\",\"name\":\"여성의류\",\"last\":true}]},"
                    + "{\"displayCategoryCode\":\"1002\",\"name\":\"여성 반팔티\",\"last\":true,\"child\":[]}"
                    + "]}}";
    // Predict = single candidate (data.predictedCategoryId + data.categoryName).
    private static final String PREDICT_FIXTURE =
            "{\"code\":\"SUCCESS\",\"data\":{\"predictedCategoryId\":\"56174\",\"categoryName\":\"여성 반팔티\"}}";

    @Override
    public String get(String path, String query, MarketplaceAccount account) {
        String body = resolve(path);
        log.info("[COUPANG-MOCK] GET {} q={} → {} bytes", path, query, body.length());
        return body;
    }

    @Override
    public String post(String path, String body, MarketplaceAccount account) {
        if (path.contains("seller-products")) {
            log.info("[COUPANG-MOCK] POST {} → register fixture", path);
            return SELLER_PRODUCT_REGISTER;
        }
        if (path.contains("categorization/predict")) {
            log.info("[COUPANG-MOCK] POST {} → predict fixture", path);
            return PREDICT_FIXTURE;
        }
        log.info("[COUPANG-MOCK] POST {} → default empty", path);
        return EMPTY;
    }

    @Override
    public String put(String path, String body, MarketplaceAccount account) {
        log.info("[COUPANG-MOCK] PUT {} → default empty", path);
        return EMPTY;
    }

    private String resolve(String path) {
        if (path.contains("ordersheets")) {
            return load(ORDERSHEETS_FIXTURE);
        }
        if (path.contains("returnRequests")) {
            return load(RETURN_REQUESTS_FIXTURE);
        }
        if (path.contains("seller-products")) {
            return SELLER_PRODUCT_FETCH;
        }
        if (path.contains("display-categories/")) {
            return DISPLAY_CATEGORIES_FIXTURE;
        }
        return EMPTY;
    }

    private String load(String classpath) {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Mock fixture 로드 실패: " + classpath, e);
        }
    }
}
