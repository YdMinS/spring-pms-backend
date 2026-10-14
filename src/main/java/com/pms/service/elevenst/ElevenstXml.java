package com.pms.service.elevenst;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * XML helpers for the 11st seller API (FEATURE_2610_13): building request bodies (EUC-KR, D-guide 1003) and
 * reading responses ({@code ClientMessage}, product search). Pure static — no Spring, no I/O.
 *
 * <p>Element lookup is by LOCAL name, so a namespaced response ({@code ns2:product}) and a plain one read the
 * same way. DOCTYPE is refused (no external entities).</p>
 */
public final class ElevenstXml {

    /** 11st request and response encoding. */
    public static final Charset EUC_KR = Charset.forName("EUC-KR");

    private ElevenstXml() {
    }

    // ---------------------------------------------------------------- building

    /** Appends {@code <tag>escaped value</tag>}; a null value appends nothing. */
    public static void element(StringBuilder xml, String tag, Object value) {
        if (value == null) {
            return;
        }
        xml.append('<').append(tag).append('>').append(escape(String.valueOf(value)))
                .append("</").append(tag).append('>');
    }

    /** Appends {@code <tag><![CDATA[value]]></tag>}; a null value appends nothing. */
    public static void cdata(StringBuilder xml, String tag, String value) {
        if (value == null) {
            return;
        }
        // "]]>" cannot appear inside one CDATA section → split it across two sections.
        xml.append('<').append(tag).append("><![CDATA[")
                .append(value.replace("]]>", "]]]]><![CDATA[>"))
                .append("]]></").append(tag).append('>');
    }

    /** XML text escaping for element content. */
    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Full request document: declaration + body, encoded as EUC-KR bytes. */
    public static byte[] document(String body) {
        return ("<?xml version=\"1.0\" encoding=\"euc-kr\"?>" + body).getBytes(EUC_KR);
    }

    // ---------------------------------------------------------------- reading

    /**
     * @return the parsed document, or {@code null} when the bytes are empty or not XML (the caller decides
     *         whether that is fatal)
     */
    public static Document parseOrNull(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // A declaration names its own encoding; a body without one is read as EUC-KR (11st's encoding).
            String head = new String(xml, 0, Math.min(xml.length, 100), StandardCharsets.ISO_8859_1);
            if (head.startsWith("<?xml") && head.contains("encoding")) {
                return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            }
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(new String(xml, EUC_KR))));
        } catch (Exception e) {
            return null;
        }
    }

    /** Trimmed text of the first element with this local name anywhere under {@code root}, or null. */
    public static String firstText(Node root, String localName) {
        if (root == null) {
            return null;
        }
        List<Element> found = elements(root, localName);
        if (found.isEmpty()) {
            return null;
        }
        String text = found.get(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /** Every element with this local name under {@code root}, in document order. */
    public static List<Element> elements(Node root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = root instanceof Document document
                ? document.getElementsByTagNameNS("*", "*")
                : ((Element) root).getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String name = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
            if (localName.equals(name)) {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * The 11st {@code message} text of a response body, or the decoded body itself when it carries none —
     * used to surface 11st's own wording as the failure reason (D60).
     */
    public static String messageOf(byte[] body) {
        String message = firstText(parseOrNull(body), "message");
        if (message != null && !message.isBlank()) {
            return message;
        }
        return body == null ? "" : new String(body, EUC_KR).trim();
    }
}
