package com.pms.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우리 저장소 호스트 해석(2609_68) — 마켓 URL 가져오기의 허용 호스트가 이 값에 달려 있다.
 */
class ImageStoragePropertiesTest {

    private ImageStorageProperties props(String type, String bucket, String region, String publicBaseUrl) {
        ImageStorageProperties p = new ImageStorageProperties();
        p.setType(type);
        p.getS3().setBucket(bucket);
        p.getS3().setRegion(region);
        p.getS3().setPublicBaseUrl(publicBaseUrl);
        return p;
    }

    @Test
    void resolvesHostFromBucketAndRegion() {
        assertThat(props("s3", "oclyx-product-images-dev", "ap-northeast-2", null).resolvePublicImageHost())
                .isEqualTo("oclyx-product-images-dev.s3.ap-northeast-2.amazonaws.com");
    }

    /** CDN·커스텀 도메인을 쓰면 그 호스트가 실제로 사진을 내주는 곳이다. */
    @Test
    void overrideWins() {
        assertThat(props("s3", "bucket", "ap-northeast-2", "https://img.example.com").resolvePublicImageHost())
                .isEqualTo("img.example.com");
    }

    /** 🔴 local 저장소는 공개 URL 이 없다 — null 이어야 쿠팡 CDN 만 허용된다. */
    @Test
    void localStorageHasNoPublicHost() {
        assertThat(props("local", "bucket", "ap-northeast-2", null).resolvePublicImageHost()).isNull();
    }

    /** 버킷 미설정(오설정)은 예외가 아니라 null 이다 — 가드를 느슨하게 만들지 않는다. */
    @Test
    void missingBucketYieldsNull() {
        assertThat(props("s3", null, "ap-northeast-2", null).resolvePublicImageHost()).isNull();
    }
}
