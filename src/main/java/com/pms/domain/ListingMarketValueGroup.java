package com.pms.domain;

/**
 * The 「마켓 값 사용」 flag groups of a channel cell (FEATURE_2611_27 / D27). Each group says whether that part of the
 * cell is sent from the cell's own values read from the market instead of the master's.
 *
 * <p>{@link #ATTRIBUTES} = the category attributes · notices (option {@code categoryAttributes} ·
 * {@code categoryNotices} and the cell {@code categoryNoticeGroup}) · {@link #TAGS} = the search tags · {@link #SHIPPING}
 * = the shipping override.</p>
 */
public enum ListingMarketValueGroup {
    ATTRIBUTES,
    TAGS,
    SHIPPING
}
