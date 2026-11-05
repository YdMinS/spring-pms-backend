package com.pms.service.elevenst;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEATURE_2611_11 / D35 — the public OpenAPI 「상품이미지검색」 answer (guide field names). */
class ElevenstProductImageClientTest {

    private static byte[] eucKr(String xml) {
        return xml.getBytes(Charset.forName("EUC-KR"));
    }

    @Test
    void parse_basicAndAdditionalImages_inGuideOrder() {
        ElevenstProductImageClient.Photos photos = ElevenstProductImageClient.parse(eucKr(
                "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><ProductImageResponse><Product>"
                        + "<ProductCode>12345678</ProductCode><ProductName>상품</ProductName>"
                        + "<BasicImage>http://i.011st.com/g/12345678_B.gif</BasicImage>"
                        + "<AddImage1>http://i.011st.com/g/12345678_A1.gif</AddImage1><AddImage2></AddImage2>"
                        + "<AddImage3>http://i.011st.com/g/12345678_A3.gif</AddImage3>"
                        + "<ImageL300>http://i.011st.com/g/12345678_L300.gif</ImageL300>"
                        + "</Product></ProductImageResponse>"));

        assertThat(photos.basic()).isEqualTo("http://i.011st.com/g/12345678_B.gif");
        assertThat(photos.additional()).isEqualTo(List.of(
                "http://i.011st.com/g/12345678_A1.gif", "http://i.011st.com/g/12345678_A3.gif"));
    }

    @Test
    void parse_errorResponse_throws() {
        assertThatThrownBy(() -> ElevenstProductImageClient.parse(eucKr(
                "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><ErrorResponse><ErrorCode>004</ErrorCode>"
                        + "<ErrorMessage>tMall.overedTraffic</ErrorMessage></ErrorResponse>")))
                .isInstanceOf(ElevenstPhotoReadException.class)
                .hasMessageContaining("004");
    }
}
