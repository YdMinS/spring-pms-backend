package com.pms.service.stock;

import com.pms.dto.request.OutboundWorkRequest;

/** 「출고 작업」 save (FEATURE_2611_34 D35 ⑤). */
public interface OutboundWorkService {

    /** Saves every line as a disposal row in one transaction — one rejected line saves nothing (D34). */
    void save(OutboundWorkRequest request);
}
