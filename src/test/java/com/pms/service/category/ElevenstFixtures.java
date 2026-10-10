package com.pms.service.category;

import java.nio.charset.Charset;

/**
 * Hand-made 11st inputs for tests (FEATURE_2610_10 / D31 — the real saved page holds a login session value and
 * is never committed). Both are EUC-KR like the real ones.
 *
 * <p>Tree: 9 top-level · 24 nodes · 13 leaves. 「스마트기기」 exists at the top level and under 「해외직구」.
 * Fee page: a decoy table first, then the fee table with a broken rowspan, a name fix (D30), exception groups,
 * 「1% 혹은 정액」, a row missing from the tree and a non-{@code N%} fee.</p>
 */
public final class ElevenstFixtures {

    public static final Charset EUC_KR = Charset.forName("EUC-KR");

    public static final String TREE_BODY =
            cat(1, "스마트기기", "10", "0", false)
            + cat(2, "스마트워치", "11", "10", true)
            + cat(2, "태블릿", "12", "10", false)
            + cat(3, "안드로이드태블릿", "121", "12", true)
            + cat(1, "주방용품", "20", "0", false)
            + cat(2, "프라이팬", "21", "20", true)
            + cat(2, "식기/홈세트", "22", "20", true)
            + cat(1, "건강관리용품", "30", "0", false)
            + cat(2, "금연용품", "31", "30", true)
            + cat(2, "수지침", "32", "30", true)
            + cat(2, "혈압계", "33", "30", true)
            + cat(1, "해외직구", "40", "0", false)
            + cat(2, "스마트기기", "41", "40", false)
            + cat(3, "해외워치", "411", "41", true)
            + cat(1, "욕실용품", "50", "0", false)
            + cat(2, "샤워기", "51", "50", true)
            + cat(1, "구강/면도", "60", "0", false)
            + cat(2, "칫솔", "61", "60", true)
            + cat(1, "비즈11번가", "70", "0", false)
            + cat(2, "사무용품", "71", "70", true)
            + cat(1, "캐주얼/유니섹스", "80", "0", false)
            + cat(2, "티셔츠", "81", "80", true)
            + cat(1, "렌털/구독 서비스", "90", "0", false)
            + cat(2, "정수기렌털", "91", "90", true);

    public static final String FEE_ROWS = """
            <tr>
              <td rowspan="1"><div class="tdW textC">가전/디지털</div></td>
              <td><div class="tdW">스마트기기</div></td>
              <td><div class="tdW textC">9%</div></td>
              <td><div class="tdW">전체 하위카테고리 동일</div></td>
            </tr>
            <tr>
              <td rowspan="4"><div class="tdW textC">생활/건강</div></td>
              <td><div class="tdW">주방용품</div></td>
              <td><div class="tdW textC">13%</div></td>
              <td><div class="tdW">기타 주방용품; 프라이팬(7%)</div></td>
            </tr>
            <tr>
              <td><div class="tdW">건강관리용품</div></td>
              <td><div class="tdW textC">13%</div></td>
              <td><div class="tdW">금연용품; 성인용품(12%), 수지침(11%)</div></td>
            </tr>
            <tr>
              <td><div class="tdW">욕실용품</div></td>
              <td rowspan="2"><div class="tdW textC">13%</div></td>
              <td rowspan="2&gt;&lt;div class=" tdw"="">전체 하위카테고리 동일</td>
            </tr>
            <tr>
              <td><div class="tdW">청소용품</div></td>
            </tr>
            <tr>
              <td><div class="tdW textC">브랜드<br>
                패션</div></td>
              <td><div class="tdW">캐쥬얼/유니섹스</div></td>
              <td><div class="tdW textC">13%</div></td>
              <td><div class="tdW"></div></td>
            </tr>
            <tr>
              <td><div class="tdW textC">여행/e쿠폰</div></td>
              <td><div class="tdW">렌털/구독 서비스</div></td>
              <td><div class="tdW textC">1%</div></td>
              <td><div class="tdW">1% 혹은 정액</div></td>
            </tr>
            <tr>
              <td><div class="tdW textC">해외직구</div></td>
              <td><div class="tdW">스마트기기</div></td>
              <td><div class="tdW textC">13%</div></td>
              <td><div class="tdW">전체 하위카테고리 동일</div></td>
            </tr>
            <tr>
              <td><div class="tdW textC">기타</div></td>
              <td><div class="tdW">주방잡화</div></td>
              <td><div class="tdW textC">정액</div></td>
              <td><div class="tdW"></div></td>
            </tr>
            """;

    private ElevenstFixtures() {
    }

    public static String cat(int depth, String name, String dispNo, String parentDispNo, boolean leaf) {
        return "<ns2:category><depth>" + depth + "</depth><dispNm>" + name + "</dispNm><dispNo>" + dispNo
                + "</dispNo><engDispYn>N</engDispYn><gblDlvYn>N</gblDlvYn><leafYn>" + (leaf ? "Y" : "N")
                + "</leafYn><parentDispNo>" + parentDispNo + "</parentDispNo></ns2:category>";
    }

    /** The tree XML envelope around {@code body}, EUC-KR encoded like the real response. */
    public static byte[] treeXml(String body) {
        return ("<?xml version=\"1.0\" encoding=\"euc-kr\" standalone=\"yes\"?>"
                + "<ns2:categorys xmlns:ns2=\"http://skt.tmall.business.openapi.spring.service.client.domain/\">"
                + body + "</ns2:categorys>").getBytes(EUC_KR);
    }

    public static byte[] treeXml() {
        return treeXml(TREE_BODY);
    }

    /** A saved-page shaped document: decoy table, then the fee table holding {@code rows}; EUC-KR. */
    public static byte[] feeHtml(String rows) {
        String html = """
                <!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
                <!-- saved from url=(0040)https://soffice.11st.co.kr/product/x -->
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=EUC-KR">
                <title>11번가 카테고리별 서비스이용료 안내</title></head>
                <body>
                <table><tbody>
                  <tr><th>구분</th><th>판매가</th><th>서비스이용료</th></tr>
                  <tr><td>예시</td><td>10,000</td><td>13%</td></tr>
                </tbody></table>
                <h2>2025년 4월 기준 대카테고리별 수수료</h2>
                <table><tbody>
                  <tr class="colsize"><td width="100"></td><td width="170"></td><td width="70"></td><td width="*"></td></tr>
                  <tr>
                    <th><div class="thW"><b>그룹군</b></div></th>
                    <th><div class="thW"><b>대카테고리</b></div></th>
                    <th><div class="thW"><b>고정가/<br>
                      예약판매</b></div></th>
                    <th><div class="thW"><b>제외 카테고리</b></div></th>
                  </tr>
                """ + rows + """
                </tbody></table>
                </body></html>
                """;
        return html.getBytes(EUC_KR);
    }

    public static byte[] feeHtml() {
        return feeHtml(FEE_ROWS);
    }
}
