package com.pms.service;

import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;

import java.util.List;

public interface ThumbnailTemplateService {

    ThumbnailTemplateResponse create(ThumbnailTemplateRequest request);

    ThumbnailTemplateResponse get(Long id);

    /** All templates for the current tenant (shared library). */
    List<ThumbnailTemplateResponse> list();

    ThumbnailTemplateResponse update(Long id, ThumbnailTemplateRequest request);

    void delete(Long id);

    /** Render a preview JPEG (non-persistent). Returns raw JPEG bytes. */
    byte[] preview(ThumbnailPreviewRequest request);
}
