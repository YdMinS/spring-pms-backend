CREATE TABLE IF NOT EXISTS shopping_list_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_id   BIGINT,
    product_id      BIGINT      NOT NULL,
    auto_qty        INT         NOT NULL DEFAULT 0,
    manual_qty      INT         NOT NULL DEFAULT 0,
    created_date    DATETIME    NOT NULL,
    modified_date   DATETIME,
    CONSTRAINT uq_shopping_list_item UNIQUE (order_item_id, product_id),
    CONSTRAINT fk_sli_order_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
    CONSTRAINT fk_sli_product    FOREIGN KEY (product_id)    REFERENCES products(id),
    INDEX idx_sli_product (product_id),
    INDEX idx_sli_order_item (order_item_id)
);

CREATE TABLE IF NOT EXISTS purchase_record (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    shopping_list_item_id   BIGINT      NOT NULL,
    purchased_on            DATE        NOT NULL,
    quantity                INT         NOT NULL,
    created_date            DATETIME    NOT NULL,
    modified_date           DATETIME,
    CONSTRAINT fk_pr_sli FOREIGN KEY (shopping_list_item_id) REFERENCES shopping_list_item(id),
    INDEX idx_pr_sli (shopping_list_item_id),
    INDEX idx_pr_purchased_on (purchased_on)
);
