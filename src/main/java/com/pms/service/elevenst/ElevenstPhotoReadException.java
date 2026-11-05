package com.pms.service.elevenst;

/**
 * The 11st photo read failed (FEATURE_2611_11 / D35). Never reaches a response — {@code ElevenstListingAdapter}
 * turns it into the 「11번가 사진을 가져오지 못했습니다」 guidance and the listing keeps its photos.
 */
public class ElevenstPhotoReadException extends RuntimeException {

    public ElevenstPhotoReadException(String message) {
        super(message);
    }
}
