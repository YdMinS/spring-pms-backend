package com.pms.dto.response;

import com.pms.domain.CollageLayout;
import com.pms.domain.CollageShape;

import java.util.List;

/**
 * The collage editor of one stage (FEATURE_2610_21 D41 · D43 · D46).
 *
 * @param stage           the edited stage and what it currently uses
 * @param bundle          2+ components (D3)
 * @param products        components, ascending id
 * @param shapes          shapes allowed for the component count (D30)
 * @param ownLayout       this stage's own collage adjusted to the current components (D43); null when the stage has
 *                        no own row or its own row is switched off
 * @param inheritedLayout the switched-on collage of the nearest upper stage, adjusted (D43); null when there is none
 * @param previewTargets  templates to preview with (D46), first = the default choice
 */
public record CollageStageResponse(
        CollageStageView stage,
        boolean bundle,
        List<CollageProductView> products,
        List<CollageShape> shapes,
        CollageLayout ownLayout,
        CollageLayout inheritedLayout,
        List<CollagePreviewTarget> previewTargets) {
}
