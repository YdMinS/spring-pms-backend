package com.pms.constants;

public class ApiConstants {

    public static final String API_BASE_PATH = "/api";
    public static final String HEALTH_CHECK_PATH = API_BASE_PATH + "/health";
    public static final String APP_NAME = "Spring PMS Backend";
    public static final String APP_VERSION = "0.0.1";

    private ApiConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
