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
