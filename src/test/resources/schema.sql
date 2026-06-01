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

CREATE TABLE category (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(100)   NOT NULL,
    platform                VARCHAR(50)    NOT NULL,
    platform_category_id    VARCHAR(50)    NOT NULL,
    parent_id               BIGINT,
    created_date            DATETIME       NOT NULL,
    modified_date           DATETIME,
    FOREIGN KEY (parent_id) REFERENCES category(id)
);

CREATE INDEX idx_category_platform ON category(platform);
CREATE INDEX idx_category_parent_id ON category(parent_id);

CREATE TABLE commission_rate (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform       VARCHAR(50)    NOT NULL,
    category_id    BIGINT,
    rate           DECIMAL(5, 4)  NOT NULL,
    is_default     BOOLEAN        NOT NULL DEFAULT FALSE,
    FOREIGN KEY (category_id) REFERENCES category(id)
);

CREATE INDEX idx_commission_rate_platform ON commission_rate(platform);
CREATE INDEX idx_commission_rate_is_default ON commission_rate(is_default);
