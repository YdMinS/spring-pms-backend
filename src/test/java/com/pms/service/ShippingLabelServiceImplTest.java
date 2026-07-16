package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
                coupangApiClient, props, marketplaceAccountRepository, new ObjectMapper());
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
        assertThat(first.productName()).isEqualTo("양말세트");            // 노출상품명(vendorItemPackageName) 우선
        assertThat(rows.get(1).productName()).isEqualTo("양말 화이트 M");  // 노출상품명 없으면 노출옵션명 폴백
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.orderId()).isEqualTo("4000019469460");            // Number → String
        assertThat(first.deliveryMessage()).isEqualTo("문앞");
        assertThat(first.shipmentBoxId()).isEqualTo("302012345678");       // 관리코드
        assertThat(first.sellerName()).isEqualTo("셀러A");
        assertThat(first.platform()).isEqualTo("COUPANG");
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
    void toXlsx_headerAndData() throws Exception {
        List<ShippingLabelRow> rows = List.of(
                new ShippingLabelRow("김철수", "01012345678", "06133", "서울시 강남구 테헤란로 1 101동 202호",
                        "양말 블랙 L", 2, "4000019469460", "문앞", "302012345678", "셀러A", "COUPANG"),
                new ShippingLabelRow("이영희", "01099998888", "07001", "서울시 서초구 1",
                        "양말 화이트 M", 1, "4000019469461", "", "302012345679", "셀러A", "COUPANG"));

        byte[] xlsx = service.toXlsx(rows);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 12);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("받는사람 이름");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("관리코드");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("01012345678");
            assertThat(row1.getCell(5).getNumericCellValue()).isEqualTo(2.0);      // 수량
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(2.0);      // 내품수량
            assertThat(row1.getCell(9).getStringCellValue()).isEqualTo("302012345678");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("이영희");
        }
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
                  {"vendorItemId":3823839899,"vendorItemPackageName":"양말세트","vendorItemName":"양말 블랙 L","shippingCount":2,"cancelCount":0,"holdCountForCancel":0},
                  {"vendorItemId":3823839900,"vendorItemName":"양말 화이트 M","shippingCount":1,"cancelCount":0,"holdCountForCancel":0},
                  {"vendorItemId":3823839901,"vendorItemName":"취소된옵션","shippingCount":1,"cancelCount":1,"holdCountForCancel":0}
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
