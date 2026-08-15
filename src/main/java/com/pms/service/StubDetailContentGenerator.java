package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Seam stub for detail-page HTML (FEATURE_2608_06 / 3b-2): the master's representative image (its
 * {@code sourceImageUrl} override, if any) plus the listing name on one line. Depends on nothing beyond
 * the cell, so it is trivially replaceable in Step 2 by a real generator without touching the wiring.
 */
@Slf4j
@Service
public class StubDetailContentGenerator implements DetailContentGenerator {

    @Override
    public String generate(ProductListing cell) {
        StringBuilder html = new StringBuilder();
        MasterProduct master = cell.getMasterProduct();
        if (master != null && StringUtils.hasText(master.getSourceImageUrl())) {
            html.append("<img src=\"").append(master.getSourceImageUrl()).append("\"/>");
        }
        html.append("<p>").append(cell.getName()).append("</p>");
        return html.toString();
    }
}
