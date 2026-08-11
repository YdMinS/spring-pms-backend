package com.pms.service;

import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;

import java.util.List;

public interface ThumbnailTemplateService {

    ThumbnailTemplateResponse create(ThumbnailTemplateRequest request);

    ThumbnailTemplateResponse get(Long id);

    /** All templates for the current tenant, optionally filtered by seller (null = no filter). */
    List<ThumbnailTemplateResponse> list(Long sellerId);

    ThumbnailTemplateResponse update(Long id, ThumbnailTemplateRequest request);

    void delete(Long id);

    /** Render a preview JPEG (non-persistent). Returns raw JPEG bytes. */
    byte[] preview(ThumbnailPreviewRequest request);
}
