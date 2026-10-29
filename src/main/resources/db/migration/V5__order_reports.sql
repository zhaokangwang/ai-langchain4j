-- 报表专用演示订单，与原客服工具的模拟订单号保持一致；金额为订单总额（人民币）。
CREATE TABLE report_orders (
    order_number VARCHAR(32) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    product VARCHAR(120) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    amount DECIMAL(18,2) NOT NULL CHECK (amount >= 0),
    status VARCHAR(32) NOT NULL,
    ordered_on DATE NOT NULL
);
CREATE INDEX idx_report_orders_owner_date ON report_orders(owner_id, ordered_on);
INSERT INTO report_orders VALUES
('A1001','demo-user','机械键盘',1,299.00,'DELIVERED',DATE '2026-10-05'),
('A1002','demo-user','无线鼠标',2,178.00,'PENDING_SHIPMENT',DATE '2026-10-20'),
('A1003','demo-user','蓝牙键盘',1,199.00,'DELIVERED',DATE '2026-10-12'),
('B2001','other-user','机械键盘',1,999.00,'DELIVERED',DATE '2026-10-08');

-- 固化查询结果，分页、统计和导出读取同一份数据，重启后仍能下载。
CREATE TABLE order_report_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_report_snapshots_owner ON order_report_snapshots(owner_id, created_at);
