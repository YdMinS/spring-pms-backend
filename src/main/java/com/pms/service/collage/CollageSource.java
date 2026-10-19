package com.pms.service.collage;

/**
 * Which stored stage a stage currently uses (FEATURE_2610_21 D41): its own row, the seller row, the master default
 * row, or none.
 */
public enum CollageSource {
    OWN,
    SELLER,
    MASTER,
    NONE
}
