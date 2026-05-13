-- H2 Test Database Schema
-- Used by application-test.yml

CREATE TABLE stock_log (
    stock_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_date DATETIME NOT NULL,
    barcode_id BIGINT NOT NULL,
    in_stock INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    stock_add INTEGER DEFAULT 0,
    stock_sub INTEGER DEFAULT 0
);

CREATE INDEX idx_stock_log_barcode_id ON stock_log(barcode_id);
CREATE INDEX idx_stock_log_created_date ON stock_log(created_date);

CREATE TABLE carrier_rate (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    carrier        VARCHAR(100)   NOT NULL,
    type           VARCHAR(50)    NOT NULL,
    cost           DECIMAL(10, 2) NOT NULL,
    effective_date DATE           NOT NULL,
    is_default     BOOLEAN        NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_carrier_rate_is_default ON carrier_rate(is_default);
