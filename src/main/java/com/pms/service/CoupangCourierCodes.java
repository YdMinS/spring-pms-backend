package com.pms.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 쿠팡 택배사 코드표 (deliveryCompanyCode → 택배사 이름).
 *
 * <p>쿠팡은 <b>택배사 목록 조회 API 를 제공하지 않는다</b> — 개발자 문서
 * (<a href="https://developers.coupang.com/ko/api/logistics/courier-code">택배사 코드</a>)의
 * 정적 표가 SSOT 이고 WING 배송관리 드롭다운도 같은 표다. 그래서 여기에 그대로 박아 둔다.
 * 문서에 없는 코드로 송장업로드를 하면 쿠팡이 거절하므로, 이 표가 <b>화이트리스트</b> 역할도 한다
 * (사용자가 고른 코드는 이 표에 있는 것만 전송된다).
 *
 * <p>⚠️ 이 표는 로컬 {@code carrier}/{@code platform_carrier_code}(택배사 관리)와 <b>다른 것</b>이다.
 * 택배사 관리는 택배비(CarrierRate)를 매기는 <b>우리 업무 데이터</b>이고, 이 표는 쿠팡이 받아주는
 * 코드 전량이다. 단건 발송처리 드롭다운은 이 표를 쓰고, 택배사 관리에 등록된 코드는 맨 위로 올린다.
 *
 * <p>순서는 문서 표 순서 그대로다(자주 쓰는 국내 택배사가 앞).
 * 갱신은 수동 — 문서에 코드가 추가되면 이 파일에 한 줄 넣는다.
 */
public final class CoupangCourierCodes {

    private static final Map<String, String> CODES = createCodes();

    private CoupangCourierCodes() {
    }

    private static Map<String, String> createCodes() {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("HYUNDAI", "롯데택배");
        codes.put("KGB", "로젠택배");
        codes.put("EPOST", "우체국");
        codes.put("HANJIN", "한진택배");
        codes.put("CJGLS", "CJ대한통운");
        codes.put("KOREX", "대한통운[합병]");
        codes.put("KDEXP", "경동택배");
        codes.put("DIRECT", "업체직송");
        codes.put("ILYANG", "일양택배");
        codes.put("CHUNIL", "천일특송");
        codes.put("AJOU", "아주택배");
        codes.put("CSLOGIS", "SC로지스");
        codes.put("DAESIN", "대신택배");
        codes.put("CVS", "CVS택배");
        codes.put("HDEXP", "합동택배");
        codes.put("DHL", "DHL");
        codes.put("UPS", "UPS");
        codes.put("FEDEX", "FEDEX");
        codes.put("REGISTPOST", "우편등기");
        codes.put("EMS", "우체국 EMS");
        codes.put("TNT", "TNT");
        codes.put("USPS", "USPS");
        codes.put("IPARCEL", "i-parcel");
        codes.put("GSMNTON", "GSM NtoN");
        codes.put("SWGEXP", "성원글로벌");
        codes.put("PANTOS", "범한판토스");
        codes.put("ACIEXPRESS", "ACI Express");
        codes.put("DAEWOON", "대운글로벌");
        codes.put("AIRBOY", "에어보이익스프레스");
        codes.put("KGLNET", "KGL네트웍스");
        codes.put("KUNYOUNG", "건영택배");
        codes.put("SLX", "SLX택배");
        codes.put("HONAM", "우리택배");
        codes.put("LINEEXPRESS", "LineExpress");
        codes.put("TWOFASTEXP", "2FastsExpress");
        codes.put("HPL", "한의사랑택배");
        codes.put("GOODSTOLUCK", "굿투럭");
        codes.put("KOREXG", "CJ대한통운특");
        codes.put("HANDEX", "한덱스");
        codes.put("BGF", "BGF포스트");
        codes.put("ECMS", "ECMS익스프레스");
        codes.put("WONDERS", "원더스퀵");
        codes.put("YONGMA", "용마로지스");
        codes.put("SEBANG", "세방택배");
        codes.put("NHLOGIS", "농협택배");
        codes.put("LOTTEGLOBAL", "롯데글로벌");
        codes.put("GSIEXPRESS", "GSI익스프레스");
        codes.put("EFS", "EFS");
        codes.put("DHLGLOBALMAIL", "DHL GlobalMail");
        codes.put("HILOGIS", "Hi택배");
        codes.put("GPSLOGIX", "GPS로직");
        codes.put("CRLX", "시알로지텍");
        codes.put("BRIDGE", "브리지로지스");
        codes.put("HOMEINNOV", "홈이노베이션로지스");
        codes.put("CWAY", "씨웨이");
        codes.put("GNETWORK", "자이언트");
        codes.put("ACEEXP", "ACE Express");
        codes.put("WEVILL", "우리동네택배");
        codes.put("FOREVERPS", "퍼레버택배");
        codes.put("WARPEX", "워펙스");
        codes.put("QXPRESS", "큐익스프레스");
        codes.put("SMARTLOGIS", "스마트로지스");
        codes.put("HOMEPICK", "홈픽택배");
        codes.put("GTSLOGIS", "GTS로지스");
        codes.put("ESTHER", "에스더쉬핑");
        codes.put("INTRAS", "로토스");
        codes.put("EUNHA", "은하쉬핑");
        codes.put("UFREIGHT", "유프레이트 코리아");
        codes.put("LSERVICE", "엘서비스");
        codes.put("TPMLOGIS", "로지스밸리");
        codes.put("ZENIELSYSTEM", "제니엘시스템");
        codes.put("ANYTRACK", "애니트랙");
        codes.put("JLOGIST", "제이로지스트");
        codes.put("CHAINLOGIS", "두발히어로(4시간당일택배)");
        codes.put("QRUN", "큐런");
        codes.put("FRESHSOLUTIONS", "프레시솔루션");
        codes.put("HIVECITY", "하이브시티");
        codes.put("HANSSEM", "한샘");
        codes.put("SFC", "SFC(Santai)");
        codes.put("JNET", "J-NET");
        codes.put("GENIEGO", "지니고");
        codes.put("PANASIA", "판아시아");
        codes.put("ELIAN", "elianpost");
        codes.put("LOTTECHILSUNG", "롯데칠성");
        codes.put("SBGLS", "SBGLS");
        codes.put("ALLTAKOREA", "올타코리아");
        codes.put("YUNDA", "yunda express");
        codes.put("VALEX", "발렉스");
        codes.put("KOKUSAI", "국제익스프레스");
        codes.put("XINPATEK", "윈핸드해운항공");
        codes.put("HEREWEGO", "탱고앤고");
        codes.put("WOONGJI", "웅지익스프레스");
        codes.put("PINGPONG", "핑퐁");
        codes.put("YDH", "YDH");
        codes.put("CARGOPLEASE", "화물부탁해");
        codes.put("LOGISPOT", "로지스팟");
        codes.put("FRESHMATES", "프레시메이트");
        codes.put("VROONG", "부릉");
        codes.put("NKLS", "NK로지솔루션");
        codes.put("DODOFLEX", "도도플렉스");
        codes.put("ETOMARS", "이투마스");
        codes.put("SHIPNERGY", "배송하기좋은날");
        codes.put("VENDORPIA", "벤더피아");
        codes.put("COSHIP", "캐나다쉬핑");
        codes.put("GDAKOREA", "지디에이코리아");
        codes.put("BABABA", "바바바로지스");
        codes.put("TEAMFRESH", "팀프레시");
        codes.put("HOME1004", "1004홈");
        codes.put("NAEUN", "나은물류");
        codes.put("ACCCARGO", "acccargo");
        codes.put("NTLPS", "엔티엘피스");
        codes.put("EKDP", "삼다수가정배송");
        codes.put("HOTSINGCARGO", "허싱카고코리아");
        codes.put("SINOEX", "SINOTRANS EXPRESS");
        codes.put("DRABBIT", "딜리래빗");
        codes.put("HOMEPICKTODAY", "홈픽오늘도착");
        codes.put("DAERIM", "대림통운");
        codes.put("LOGISPARTNER", "로지스파트너");
        codes.put("GOBOX", "고박스");
        codes.put("FASTBOX", "패스트박스");
        codes.put("PANSTAR", "팬스타국제특송");
        codes.put("ACTCORE", "에이씨티앤코아물류");
        codes.put("KJT", "케이제이티");
        codes.put("THEBAO", "더바오");
        codes.put("RUSH", "오늘회러쉬");
        codes.put("KT", "kt express");
        codes.put("IBP", "ibpcorp");
        codes.put("HY", "HY");
        codes.put("LOGISVALLEY", "로지스밸리");
        codes.put("TODAY", "투데이");
        codes.put("ONEDAYLOGIS", "라스트마일시스템즈");
        codes.put("HKHOLDINGS", "에이치케이홀딩스");
        codes.put("JIKGUMOON", "직구문");
        codes.put("CUBEFLOW", "큐브플로우");
        codes.put("SHFLY", "성훈물류");
        codes.put("GBS", "지비에스");
        codes.put("BANPOOM", "반품구조대");
        codes.put("GLOVIS", "현대글로비스");
        codes.put("ARGO", "아르고");
        codes.put("JMNP", "딜리박스");
        codes.put("SELC", "삼성로지텍");
        codes.put("MTINTER", "엠티인터네셔널");
        codes.put("GDSP", "골드스넵스");
        codes.put("TODAYPICKUP", "오늘의픽업");
        codes.put("YJSGLOBAL", "yjs글로벌");
        codes.put("DUXGLOBAL", "유로택배");
        codes.put("INTERLOGIS", "인터로지스");
        codes.put("WOOJIN", "우진인터로지스");
        codes.put("GHSPEED", "지에이치스피드");
        codes.put("WIDETECH", "와이드테크");
        codes.put("ECOHAI", "에코하이");
        codes.put("TONAMI", "토나미");
        codes.put("DAIICHI", "제1화물");
        codes.put("FUKUYAMA", "후쿠야마통운");
        codes.put("KURLYNEXTMILE", "컬리넥스트마일");
        codes.put("ARAMEX", "ARAMEX");
        codes.put("BISNZ", "BISNZ");
        codes.put("INNOS", "이노스");
        codes.put("SEORIM", "서림물류");
        codes.put("WEMOVE", "위무브");
        codes.put("POOLATHOME", "풀앳홈");
        codes.put("SPARKLE", "스파클직배송");
        codes.put("ICS", "ICS");
        codes.put("HANMI", "한미포스트");
        codes.put("CAINIAO", "CAINIAO");
        codes.put("HWATONG", "화통");
        codes.put("ESTLA", "이스트라");
        codes.put("IK", "IK물류");
        codes.put("PULMUONEWATER", "풀무원샘물");
        codes.put("TSG", "티에스지로지스");
        codes.put("OCS", "ocs코리아");
        codes.put("MDLOGIS", "모든로지스");
        codes.put("GCS", "지씨에스");
        codes.put("FTF", "물류대장LCS");
        codes.put("HUBNET", "Hubnet Logistics");
        codes.put("WINION_3P", "위니온로지스");
        codes.put("WOORIHB", "우리한방택배");
        codes.put("LETUS", "레터스");
        codes.put("JWTNL", "JWTNL");
        codes.put("JCLS", "JCLS");
        codes.put("GKGLOBAL", "지케이글로벌");
        codes.put("GONELO", "고넬로");
        codes.put("CASA", "신세계까사");
        codes.put("BRCH", "비알씨에이치");
        codes.put("HLDE", "W7");
        codes.put("MORNINGGLOBAL", "모닝글로벌");
        codes.put("DNDN", "든든택배");
        codes.put("KGBLS", "KGB로지스");
        codes.put("KGBPS", "KGB택배");
        codes.put("DONGBU", "드림택배");
        codes.put("YELLOW", "옐로우캡");
        codes.put("INNOGIS", "GTX로지스");
        codes.put("DADREAM", "다드림");
        codes.put("IQS", "굿스포스트");
        codes.put("SFEXPRESS", "순풍택배");
        codes.put("LGE", "LG전자");
        codes.put("WINION", "위니온");
        codes.put("WINION2", "위니온(에어컨)");
        return Collections.unmodifiableMap(codes);
    }

    /** 코드 → 이름 전체(문서 표 순서 유지). */
    public static Map<String, String> all() {
        return CODES;
    }

    /** 쿠팡이 받아주는 코드인지. */
    public static boolean contains(String code) {
        return code != null && CODES.containsKey(code);
    }

    /** 코드의 표시 이름(미등록 코드면 코드 자체를 돌려준다 — 표시가 비지 않게). */
    public static String nameOf(String code) {
        return CODES.getOrDefault(code, code);
    }
}
