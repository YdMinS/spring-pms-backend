package com.pms.controller;

import com.pms.constants.ApiConstants;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.HealthCheckResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping(ApiConstants.HEALTH_CHECK_PATH)
    public ResponseEntity<ResponseDTO<HealthCheckResponse>> healthCheck() {
        HealthCheckResponse response = HealthCheckResponse.builder()
                .status("UP")
                .applicationName(ApiConstants.APP_NAME)
                .version(ApiConstants.APP_VERSION)
                .build();

        return ResponseEntity.ok(ResponseDTO.success("Application is running", response));
    }
}
