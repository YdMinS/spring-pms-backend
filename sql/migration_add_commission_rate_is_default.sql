-- Migration: Add is_default column to commission_rate table
-- Date: 2026-06-01
-- Purpose: Support default commission rate per platform

-- Add is_default column to commission_rate table
ALTER TABLE commission_rate
ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- Create index on is_default for query optimization
CREATE INDEX idx_commission_rate_is_default ON commission_rate(is_default);

-- (Optional) Create category table if it doesn't exist
-- This supports category-specific commission rates
CREATE TABLE IF NOT EXISTS category (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(100)   NOT NULL,
    platform                VARCHAR(50)    NOT NULL,
    platform_category_id    VARCHAR(50)    NOT NULL,
    parent_id               BIGINT,
    created_date            DATETIME       NOT NULL,
    modified_date           DATETIME,
    FOREIGN KEY (parent_id) REFERENCES category(id),
    INDEX idx_category_platform (platform),
    INDEX idx_category_parent_id (parent_id)
);

-- (Optional) Add FK constraint if category_id column doesn't have one
-- ALTER TABLE commission_rate
-- ADD CONSTRAINT fk_commission_rate_category
-- FOREIGN KEY (category_id) REFERENCES category(id);
