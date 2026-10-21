package com.pms.service.esm;

/**
 * Which site seller IDs go into the token {@code ssi} claim (FEATURE_2610_26 / D6). Every call uses {@link #BOTH}
 * except the two lookups the guide says take one site ID only (site categories, recommended options).
 */
public enum EsmSite {
    BOTH,
    GMARKET,
    AUCTION
}
