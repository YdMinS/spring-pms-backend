package com.pms.service.category;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the public 11st category tree XML into {@link ElevenstCategoryNode}s (FEATURE_2610_10 / D7).
 *
 * <p>The document is {@code <ns2:categorys><ns2:category>…</ns2:category>…}; each category carries
 * {@code depth · dispNm · dispNo · parentDispNo · leafYn}. The XML declaration names the encoding (EUC-KR),
 * so the bytes go to the XML parser as they are. DOCTYPE is refused (no external entities).</p>
 */
@Component
public class ElevenstCategoryXmlParser {

    private static final String UNREADABLE = "11번가 카테고리 응답을 읽지 못했습니다.";

    /**
     * @param xml the raw response bytes
     * @return every category in document order
     * @throws IllegalArgumentException (→ 400) when the bytes are not that document or hold no category
     */
    public List<ElevenstCategoryNode> parse(byte[] xml) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException(UNREADABLE, e);
        }

        NodeList categories = doc.getElementsByTagNameNS("*", "category");
        List<ElevenstCategoryNode> nodes = new ArrayList<>(categories.getLength());
        for (int i = 0; i < categories.getLength(); i++) {
            Element category = (Element) categories.item(i);
            String dispNo = child(category, "dispNo");
            String name = child(category, "dispNm");
            String parentDispNo = child(category, "parentDispNo");
            String depth = child(category, "depth");
            if (dispNo.isEmpty() || name.isEmpty() || parentDispNo.isEmpty() || !depth.matches("\\d+")) {
                throw new IllegalArgumentException(UNREADABLE);
            }
            nodes.add(new ElevenstCategoryNode(dispNo, name, parentDispNo, Integer.parseInt(depth),
                    "Y".equals(child(category, "leafYn"))));
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(UNREADABLE);
        }
        return nodes;
    }

    /** Trimmed text of the first direct child element with that local name; "" when absent. */
    private static String child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }
}
