package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Seller;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.OrderItemUpserter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ShippingLabelServiceImpl 조회·펼침·xlsx 테스트.
 *
 * CoupangApiClient·MarketplaceAccountRepository 는 @Mock, ObjectMapper·CoupangProperties 는 실제 인스턴스.
 */
@ExtendWith(MockitoExtension.class)
class ShippingLabelServiceImplTest {

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderItemUpserter orderItemUpserter;

    private ShippingLabelServiceImpl service;
    private MarketplaceAccount coupangAccount;

    @BeforeEach
    void setUp() {
        Seller seller = Seller.builder().id(1L).sellerName("셀러A").businessRegistration("123-45-67890").build();
        coupangAccount = MarketplaceAccount.builder()
                .id(1L).seller(seller).platform("COUPANG").vendorId("A00012345")
                .accessKey("ak").secretKey("sk").isActive(true).build();

        CoupangProperties props = new CoupangProperties();
        props.setInstructDays(14);

        service = new ShippingLabelServiceImpl(
                coupangApiClient, props, marketplaceAccountRepository, new ObjectMapper(),
                orderItemRepository, orderItemUpserter);
    }

    @Test
    void collectRows_flattensAndMaps() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());

        List<ShippingLabelRow> rows = service.collectRows(null);

        assertThat(rows).hasSize(2);                    // 취소분(발주가능 0) 라인 제외
        ShippingLabelRow first = rows.get(0);
        assertThat(first.receiverName()).isEqualTo("김철수");
        assertThat(first.receiverPhone()).isEqualTo("01012345678");        // E.164 → 국내
        assertThat(first.postCode()).isEqualTo("06133");
        assertThat(first.address()).isEqualTo("서울시 강남구 테헤란로 1 101동 202호");
        assertThat(first.productName()).isEqualTo("양말세트");            // 등록상품명(sellerProductName) 우선
        assertThat(rows.get(1).productName()).isEqualTo("양말 화이트 M");  // 등록상품명 없으면 노출옵션명(vendorItemName) 폴백
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.orderId()).isEqualTo("4000019469460");            // Number → String
        assertThat(first.deliveryMessage()).isEqualTo("문앞");
        assertThat(first.shipmentBoxId()).isEqualTo("302012345678");       // 관리코드
        assertThat(first.sellerName()).isEqualTo("셀러A");
        assertThat(first.platform()).isEqualTo("COUPANG");
    }

    @Test
    void 시트생성시조회한주문을적재한다() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());

        service.collectRows(null);

        // 시트에 실린 주문은 DB 에도 남는다 — 발송처리가 폴백에 기대지 않게 한다(PLAN 2609_13 D1).
        verify(orderItemUpserter).upsertBoxes(eq(coupangAccount), any());
    }

    @Test
    void 적재실패해도시트생성은계속된다() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());
        willThrow(new RuntimeException("boom")).given(orderItemUpserter).upsertBoxes(any(), any());

        List<ShippingLabelRow> rows = service.collectRows(null);

        // 다운로드가 우선이다(D6) — 행 수·내용이 정상 케이스와 동일해야 한다.
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).productName()).isEqualTo("양말세트");
    }

    @Test
    void collectRows_excludesCancelledLines() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());

        List<ShippingLabelRow> rows = service.collectRows(null);

        // shippingCount 1 · cancelCount 1 → 발주가능 0 → "취소된옵션" 행 없음
        assertThat(rows).extracting(ShippingLabelRow::productName).doesNotContain("취소된옵션");
    }

    @Test
    void collectRows_keepsPendingCancelLines() {
        // 취소대기(holdCountForCancel)만 걸린 상품준비중 라인은 미확정이므로 숨기지 않는다.
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxHeldLine());

        List<ShippingLabelRow> rows = service.collectRows(null);

        assertThat(rows).hasSize(1);
        ShippingLabelRow held = rows.get(0);
        assertThat(held.productName()).isEqualTo("취소대기옵션");
        assertThat(held.quantity()).isEqualTo(2);   // shipping 2 − cancel 0 (hold 는 안 뺌)
    }

    @Test
    void collectRows_paginatesUntilNextTokenBlank() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(pageWithToken("t1"), pageWithToken(""));

        List<ShippingLabelRow> rows = service.collectRows(null);

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(rows).hasSize(2);                    // 페이지당 1행 × 2
    }

    @Test
    void collectRows_queriesCoupangAccountsOnly() {
        Seller seller = Seller.builder().id(2L).sellerName("셀러B").businessRegistration("999-88-77777").build();
        MarketplaceAccount naver = MarketplaceAccount.builder()
                .id(2L).seller(seller).platform("NAVER").vendorId("N001")
                .accessKey("ak").secretKey("sk").isActive(true).build();
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(naver, coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());

        service.collectRows(null);

        // NAVER 계정은 조회하지 않고 COUPANG 계정만 호출
        verify(coupangApiClient, times(1)).get(anyString(), anyString(), eq(coupangAccount));
    }

    @Test
    void previewRows_mapsRowKeyAndDefaults() {
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(coupangAccount));
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBoxThreeLines());

        List<ShippingLabelPreviewRow> rows = service.previewRows(null);

        assertThat(rows).hasSize(2);                                        // 취소분(발주가능 0) 제외
        assertThat(rows.get(0).rowKey()).isEqualTo("302012345678:3823839899");
        assertThat(rows.get(0).parcelQuantity()).isEqualTo(1);             // 기본 택배수량
    }

    @Test
    void toXlsx_writesParcelAndInnerQuantity() throws Exception {
        List<ShippingLabelRow> rows = List.of(
                new ShippingLabelRow("김철수", "01012345678", "06133", "서울시 강남구 테헤란로 1 101동 202호",
                        "양말 블랙 L", 2, 1, "3823839899",
                        "4000019469460", "문앞", "302012345678", "셀러A", "COUPANG"),
                new ShippingLabelRow("이영희", "01099998888", "07001", "서울시 서초구 1",
                        "양말 화이트 M", 1, 1, "3823839900",
                        "4000019469461", "", "302012345679", "셀러A", "COUPANG"));

        byte[] xlsx = service.toXlsx(rows);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 12);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("받는사람 이름");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("관리코드");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("01012345678");
            assertThat(row1.getCell(5).getNumericCellValue()).isEqualTo(1.0);      // 택배수량(기본 1)
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(2.0);      // 내품수량(주문 개수)
            assertThat(row1.getCell(9).getStringCellValue()).isEqualTo("302012345678");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("이영희");
        }
    }

    @Test
    void toXlsx_honorsEditedParcelQuantity() throws Exception {
        List<ShippingLabelRow> rows = List.of(
                new ShippingLabelRow("김철수", "01012345678", "06133", "서울시 강남구 테헤란로 1 101동 202호",
                        "양말 블랙 L", 2, 3, "3823839899",
                        "4000019469460", "문앞", "302012345678", "셀러A", "COUPANG"));

        byte[] xlsx = service.toXlsx(rows);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row1 = wb.getSheetAt(0).getRow(1);
            assertThat(row1.getCell(5).getNumericCellValue()).isEqualTo(3.0);      // 편집 택배수량 반영
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(2.0);      // 내품수량 유지
        }
    }

    // --- 주문 단건 preview (V2 by-order) ---

    @Test
    void previewRowsByOrder_flattensAllLinesOfOrder() {
        givenCoupangOrder();
        given(coupangApiClient.get(anyString(), eq(""), any())).willReturn(oneBoxThreeLines());

        List<ShippingLabelPreviewRow> rows = service.previewRowsByOrder(1L);

        assertThat(rows).hasSize(2);                                        // 전량취소 라인 제외
        assertThat(rows.get(0).rowKey()).isEqualTo("302012345678:3823839899");
        assertThat(rows.get(0).parcelQuantity()).isEqualTo(1);
        assertThat(rows.get(0).sellerName()).isEqualTo("셀러A");
    }

    @Test
    void previewRowsByOrder_buildsPathWithVendorAndOrderId() {
        givenCoupangOrder();
        given(coupangApiClient.get(anyString(), eq(""), any())).willReturn(oneBoxThreeLines());

        service.previewRowsByOrder(1L);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).get(path.capture(), eq(""), any());
        assertThat(path.getValue()).contains("A00012345").contains("4000019469460");
    }

    @Test
    void previewRowsByOrder_throwsWhenOrderMissing() {
        given(orderItemRepository.findWithAccountAndSellerById(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.previewRowsByOrder(9L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
    }

    @Test
    void previewRowsByOrder_rejectsNonCoupangOrder() {
        Seller seller = Seller.builder().id(2L).sellerName("셀러B").businessRegistration("999-88-77777").build();
        MarketplaceAccount naver = MarketplaceAccount.builder()
                .id(2L).seller(seller).platform("NAVER").vendorId("N001")
                .accessKey("ak").secretKey("sk").isActive(true).build();
        OrderItem order = OrderItem.builder().id(1L).externalOrderId("4000019469460")
                .marketplaceAccount(naver).platform("NAVER").build();
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.previewRowsByOrder(1L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
    }

    @Test
    void previewRowsByOrder_throwsWhenCoupangFails() {
        givenCoupangOrder();
        given(coupangApiClient.get(anyString(), eq(""), any()))
                .willThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.previewRowsByOrder(1L))
                .isInstanceOf(IllegalStateException.class);       // 빈 리스트로 감추지 않는다
    }

    /** 쿠팡 계정에 묶인 주문 라인 1건 스텁 (by-order 테스트 공통 given). */
    private void givenCoupangOrder() {
        OrderItem order = OrderItem.builder().id(1L).externalOrderId("4000019469460")
                .marketplaceAccount(coupangAccount).platform("COUPANG").build();
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(order));
    }

    // --- canned JSON ---

    private String oneBoxThreeLines() {
        return """
            {"code":200,"message":"OK","nextToken":"",
             "data":[
               {"shipmentBoxId":302012345678,"orderId":4000019469460,"status":"INSTRUCT","parcelPrintMessage":"문앞",
                "receiver":{"name":"김철수","safeNumber":"+821012345678",
                            "addr1":"서울시 강남구 테헤란로 1","addr2":"101동 202호","postCode":"06133"},
                "orderItems":[
                  {"vendorItemId":3823839899,"sellerProductName":"양말세트","vendorItemName":"양말 블랙 L","shippingCount":2,"cancelCount":0,"holdCountForCancel":0},
                  {"vendorItemId":3823839900,"vendorItemName":"양말 화이트 M","shippingCount":1,"cancelCount":0,"holdCountForCancel":0},
                  {"vendorItemId":3823839901,"vendorItemName":"취소된옵션","shippingCount":1,"cancelCount":1,"holdCountForCancel":0}
                ]}
             ]}
            """;
    }

    // 취소대기(hold)만 걸린 라인 1건 — 확정취소 아님 → 노출되어야 함
    private String oneBoxHeldLine() {
        return """
            {"code":200,"message":"OK","nextToken":"",
             "data":[
               {"shipmentBoxId":302012345680,"orderId":4000019469462,"status":"INSTRUCT","parcelPrintMessage":"",
                "receiver":{"name":"박지성","safeNumber":"+821011112222",
                            "addr1":"서울시 마포구","addr2":"","postCode":"04001"},
                "orderItems":[
                  {"vendorItemId":3823839902,"vendorItemName":"취소대기옵션","shippingCount":2,"cancelCount":0,"holdCountForCancel":2}
                ]}
             ]}
            """;
    }

    // 페이지마다 고유한 1줄 (token 으로 구분)
    private String pageWithToken(String token) {
        String suffix = token.isBlank() ? "P2" : "P1";
        return """
            {"nextToken":"%s","data":[
               {"shipmentBoxId":"B-%s","orderId":"O-%s","status":"INSTRUCT","parcelPrintMessage":"",
                "receiver":{"name":"수령인","safeNumber":"+821000000000","addr1":"주소","addr2":"","postCode":"00000"},
                "orderItems":[{"vendorItemId":"I-%s","vendorItemName":"상품","shippingCount":1}]}
            ]}
            """.formatted(token, suffix, suffix, suffix);
    }
}
