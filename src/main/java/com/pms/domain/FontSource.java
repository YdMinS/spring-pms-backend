package com.pms.domain;

/** Origin of a {@link FontAsset}: shipped with the app (classpath) or uploaded by a tenant (storage). */
public enum FontSource {
    BUNDLED,
    UPLOADED
}
