package com.pms.service.sales;

import com.pms.dto.response.ChannelSalesResponse;
import com.pms.dto.response.ProductProfitResponse;
import com.pms.dto.response.SellerSalesResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 매출 집계 — <b>오늘까지의 매출</b>에 답한다 (FEATURE_2609_30 / PLAN D2 · D4 · D14).
 *
 * <p>🔴 <b>정산 원장을 읽어 매출을 만들지 않는다.</b> 매출의 정본은 {@code order_line} 이다(D2).
 * 정산 API 두 개는 축이 매출인식일(구매확정·배송완료 +3일)이라 오늘 것을 아예 주지 못하고,
 * 두 축을 섞으면 <i>"어제 매출이 왜 바뀌었지"</i> 가 반드시 나온다. 이 서비스가 정산에서 가져오는 것은
 * <b>"받을 돈"(pendingPayout)과 대사 배지뿐</b>이며, 그것도 기간 필터 없이 그대로 옮긴다(D4).
 *
 * <p>세 메서드는 <b>같은 집계 경로 하나</b>({@code OrderLineRepository.aggregateSales})를 쓰고 그룹 키만
 * 바꾼다 — 그래야 판매자 합계와 상품별 목록의 합계가 어긋나지 않는다.
 *
 * <p>기간 기본값: {@code to} = 오늘, {@code from} = 이번 달 1일. {@code from > to} 는 400.
 */
public interface SalesStatsService {

    /** ① 판매자별 한 줄. {@code sellerId} 가 null 이면 전 판매자. */
    List<SellerSalesResponse> summary(LocalDate from, LocalDate to, Long sellerId);

    /** ② 채널(계정)별 한 줄. 현금주의({@code paidAmount})는 여기서만 제공한다(D4-1). */
    List<ChannelSalesResponse> byChannel(LocalDate from, LocalDate to, Long sellerId);

    /**
     * ③ 상품별 수익성.
     *
     * @param crossChannel true = 마스터 상품 단위로 합친다 / false = 마스터 × 채널로 쪼갠다
     */
    List<ProductProfitResponse> byProduct(LocalDate from, LocalDate to, Long sellerId, boolean crossChannel);
}
