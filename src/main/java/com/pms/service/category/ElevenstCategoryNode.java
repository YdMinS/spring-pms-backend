package com.pms.service.category;

/**
 * One node of the public 11st category tree (FEATURE_2610_10 / D7).
 *
 * @param dispNo       the 11st category code ({@code dispNo})
 * @param name         the node name ({@code dispNm})
 * @param parentDispNo the parent code ({@code parentDispNo}); {@code "0"} = top level
 * @param depth        the level, 1 = top
 * @param leaf         {@code leafYn == Y} — only leaves keep the 11st code in {@code platform_category} (D24)
 */
public record ElevenstCategoryNode(String dispNo, String name, String parentDispNo, int depth, boolean leaf) {
}
