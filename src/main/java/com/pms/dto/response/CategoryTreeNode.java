package com.pms.dto.response;

/**
 * One node of the oclyx standard-category tree browse (FEATURE_2608_06 / 52).
 *
 * @param id   category id
 * @param name category label
 * @param leaf true when the category has no children (a selectable leaf)
 */
public record CategoryTreeNode(Long id, String name, boolean leaf) {
}
