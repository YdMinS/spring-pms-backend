package com.pms.service.category;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ElevenstCategoryXmlParser} (FEATURE_2610_10 / D7): EUC-KR tree read in document order, and the 400 for
 * a body that is not the category document (DOCTYPE refused).
 */
class ElevenstCategoryXmlParserTest {

    private final ElevenstCategoryXmlParser parser = new ElevenstCategoryXmlParser();

    @Test
    void parse_eucKrTree_readsEveryNodeInDocumentOrder() {
        List<ElevenstCategoryNode> nodes = parser.parse(ElevenstFixtures.treeXml());

        assertThat(nodes).hasSize(24);
        assertThat(nodes.get(0)).isEqualTo(new ElevenstCategoryNode("10", "스마트기기", "0", 1, false));
        assertThat(nodes.get(3)).isEqualTo(new ElevenstCategoryNode("121", "안드로이드태블릿", "12", 3, true));
        assertThat(nodes.stream().filter(ElevenstCategoryNode::leaf).count()).isEqualTo(13);
    }

    @Test
    void parse_notTheCategoryDocument_throws400() {
        assertThatThrownBy(() -> parser.parse("<html><body>점검 중</body></html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 카테고리 응답을 읽지 못했습니다.");
        assertThatThrownBy(() -> parser.parse(
                ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                        + "<categorys><category><dispNo>&e;</dispNo></category></categorys>")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 카테고리 응답을 읽지 못했습니다.");
    }
}
