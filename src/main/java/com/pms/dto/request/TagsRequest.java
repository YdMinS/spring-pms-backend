package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tag replacement body (33) for both the master pool and a channel cell's raw tags. Only null is rejected —
 * an empty list is allowed and clears the tags. The list is order-preserving deduped on save.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagsRequest {

    @NotNull
    private List<String> tags;
}
