SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 制糖厂 · 榨季压榨与结晶成品管理
-- 库名 sugar_refinery；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。
-- 品质三指标（糖度 / 纯度 / 纤维分）在 Java 侧是一个 record 组件（@CompositeType），
-- 落库时摊平成本表的 brix / purity / fiber 三列。
-- 投料交接闭环：cane_intake.allocated_kg（已交接净重）与 press_station.occupied_kg
-- （已占用千克）由交接事务维护，feed_batch 落每一笔投料批；三处数值同事务起落。

CREATE DATABASE IF NOT EXISTS sugar_refinery
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE sugar_refinery;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS feed_batch;
DROP TABLE IF EXISTS sugar_quality_inspection;
DROP TABLE IF EXISTS sugar_batch;
DROP TABLE IF EXISTS pan_wash;
DROP TABLE IF EXISTS evap_pan;
DROP TABLE IF EXISTS press_station;
DROP TABLE IF EXISTS cane_intake;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 甘蔗进厂验收 ----------------
CREATE TABLE cane_intake (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no     VARCHAR(32) NOT NULL UNIQUE COMMENT '磅单号',
    supplier      VARCHAR(64) NOT NULL COMMENT '蔗农 / 合作社',
    truck_no      VARCHAR(24) NOT NULL,
    gross_kg      DECIMAL(12,2) NOT NULL COMMENT '毛重',
    tare_kg       DECIMAL(12,2) NOT NULL COMMENT '皮重',
    net_kg        DECIMAL(12,2) NOT NULL COMMENT '净重 = 毛重 - 皮重 - 扣杂',
    deduction_kg  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '扣杂',
    allocated_kg  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '已交接投料的净重（投料交接闭环维护）',
    -- ↓↓↓ @CompositeType record 组件摊平出来的三列
    brix          DECIMAL(5,2)  NOT NULL COMMENT '糖度 Brix',
    purity        DECIMAL(5,2)  NOT NULL COMMENT '纯度 %',
    fiber         DECIMAL(5,2)  NOT NULL COMMENT '纤维分 %',
    -- ↑↑↑
    result        VARCHAR(16) NOT NULL COMMENT '合格/扣杂/拒收',
    status        VARCHAR(16) NOT NULL COMMENT '待验/已验收/已拒收',
    intake_at     DATETIME,
    -- 兜底防线：已交接量永远落在 [0, 净重] 区间（正常路径由行锁 + 事务内校验保证）
    CONSTRAINT ck_intake_alloc CHECK (allocated_kg >= 0 AND allocated_kg <= net_kg)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 压榨机组 ----------------
-- 转检修在同一事务里把负荷归零并记录本次检修时间（last_maintain_at）；
-- last_request_id 是状态流转的幂等键：重复点击 / 超时重试只生效一次，不覆盖检修时间。
CREATE TABLE press_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    design_capacity_tph DECIMAL(8,2) NOT NULL COMMENT '设计处理量 吨/小时',
    current_load_tph    DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '当前负荷',
    occupied_kg         DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '投料批已占用（千克，投料交接闭环维护）',
    status              VARCHAR(16) NOT NULL COMMENT '运行/停机/检修',
    last_maintain_at    DATETIME COMMENT '最近检修时间（转检修同事务写入）',
    last_request_id     VARCHAR(36) COMMENT '最近一次状态流转的幂等键',
    -- 兜底防线①：累计占用不得超过设计处理量对应的千克数
    CONSTRAINT ck_press_occupied CHECK (occupied_kg >= 0 AND occupied_kg <= design_capacity_tph * 1000),
    -- 兜底防线②：检修中的机组负荷必须已归零且留有本次检修时间
    CONSTRAINT ck_press_maintain CHECK
      (status <> '检修' OR (current_load_tph = 0 AND last_maintain_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 投料批（投料交接单） ----------------
-- 值班员把已验收磅单拆成一笔或多笔投料批，分配给运行中的压榨机组。
-- request_id 是幂等键：值班员一次「提交交接」意图只落一笔，
-- 重复点击 / 请求超时重试带同一 request_id，唯一索引保证不会重复扣减。
CREATE TABLE feed_batch (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no      VARCHAR(32) NOT NULL UNIQUE COMMENT '投料批号',
    request_id    VARCHAR(36) NOT NULL UNIQUE COMMENT '幂等键：同一交接请求只落一笔',
    intake_id     BIGINT NOT NULL COMMENT '来源验收磅单',
    ticket_no     VARCHAR(32) NOT NULL COMMENT '磅单号快照',
    station_id    BIGINT NOT NULL COMMENT '目标压榨机组',
    station_name  VARCHAR(64) NOT NULL COMMENT '机组名快照',
    planned_kg    DECIMAL(12,2) NOT NULL COMMENT '计划投料量',
    actual_kg     DECIMAL(12,2) COMMENT '实际投料量（完成后回填）',
    status        VARCHAR(16) NOT NULL COMMENT '待投料/投料中/已完成/已撤回',
    created_at    DATETIME,
    started_at    DATETIME,
    completed_at  DATETIME,
    cancelled_at  DATETIME,
    CONSTRAINT fk_feed_intake FOREIGN KEY (intake_id) REFERENCES cane_intake(id),
    CONSTRAINT fk_feed_station FOREIGN KEY (station_id) REFERENCES press_station(id),
    CONSTRAINT ck_feed_planned CHECK (planned_kg > 0),
    CONSTRAINT ck_feed_actual CHECK (actual_kg IS NULL OR (actual_kg > 0 AND actual_kg <= planned_kg))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 蒸发 / 结晶罐 ----------------
CREATE TABLE evap_pan (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    pan_code    VARCHAR(24) NOT NULL UNIQUE,
    pan_name    VARCHAR(64) NOT NULL,
    pan_type    VARCHAR(16) NOT NULL COMMENT '蒸发罐/结晶罐',
    capacity_m3 DECIMAL(8,2) NOT NULL,
    status      VARCHAR(16) NOT NULL COMMENT '运行/洗罐/停用',
    last_wash_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 洗罐排程（洗罐安排） ----------------
-- 蒸发罐 / 结晶罐的洗罐闭环：待执行 → 清洗中 → 已完成（待执行可取消）。
-- active_pan_id 是生成列：未完成（待执行/清洗中）时等于 pan_id，否则为 NULL，
-- 配合唯一索引在数据库层兜底「同一罐不能同时存在两个未完成的洗罐安排」。
-- 完成时间 completed_at 与 evap_pan.last_wash_at 由应用在同一事务写入，
-- 并把罐转回「运行」恢复运行资格，不可能只改状态没有洗罐时间。
CREATE TABLE pan_wash (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    wash_no       VARCHAR(32) NOT NULL UNIQUE COMMENT '排程单号',
    pan_id        BIGINT NOT NULL COMMENT '目标罐',
    pan_code      VARCHAR(24) NOT NULL COMMENT '罐号快照',
    pan_name      VARCHAR(64) NOT NULL COMMENT '罐名快照',
    pan_type      VARCHAR(16) NOT NULL COMMENT '罐型快照：蒸发罐/结晶罐，清洗中不可改换',
    window_at     DATETIME NOT NULL COMMENT '洗罐窗口（计划开始时间）',
    status        VARCHAR(16) NOT NULL COMMENT '待执行/清洗中/已完成/已取消',
    created_at    DATETIME,
    started_at    DATETIME,
    completed_at  DATETIME COMMENT '完成时间，与罐体 last_wash_at 同事务写入',
    cancelled_at  DATETIME,
    failed_reason VARCHAR(255) COMMENT '最近一次执行失败原因（只留痕，不改状态）',
    failed_at     DATETIME COMMENT '最近一次执行失败时间',
    active_pan_id BIGINT GENERATED ALWAYS AS
                    (CASE WHEN status IN ('待执行', '清洗中') THEN pan_id ELSE NULL END) STORED
                    COMMENT '未完成安排对应罐 id（唯一索引用）',
    CONSTRAINT fk_wash_pan FOREIGN KEY (pan_id) REFERENCES evap_pan(id),
    CONSTRAINT ck_wash_status CHECK
      ((status = '已完成' AND completed_at IS NOT NULL AND started_at IS NOT NULL)
       OR (status = '清洗中' AND started_at IS NOT NULL AND completed_at IS NULL)
       OR (status = '待执行' AND started_at IS NULL AND completed_at IS NULL)
       OR (status = '已取消' AND cancelled_at IS NOT NULL)),
    CONSTRAINT uq_active_pan UNIQUE (active_pan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 成品糖批次 ----------------
CREATE TABLE sugar_batch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no        VARCHAR(32) NOT NULL UNIQUE,
    grade           VARCHAR(16) NOT NULL COMMENT '白砂糖/赤砂糖/绵白糖/冰糖',
    quantity_kg     DECIMAL(12,2) NOT NULL,
    -- ↓↓↓ 同样是 @CompositeType record 组件
    brix            DECIMAL(5,2) NOT NULL,
    purity          DECIMAL(5,2) NOT NULL,
    fiber           DECIMAL(5,2) NOT NULL DEFAULT 0,
    -- ↑↑↑
    warehouse       VARCHAR(24) NOT NULL COMMENT '1号糖仓/2号糖仓/3号糖仓',
    status          VARCHAR(16) NOT NULL COMMENT '待复检/已入库/已发运',
    current_version INT NOT NULL DEFAULT 0 COMMENT '当前有效复检版本，0为待复检',
    produced_at     DATETIME,
    CONSTRAINT ck_sugar_status CHECK (status IN ('待复检', '已入库', '已发运')),
    CONSTRAINT ck_sugar_current_version CHECK (current_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 成品糖质量复检 ----------------
-- 复检按批号 + 版本只追加，不覆盖历史；request_id 保证重复点击 / 超时重试只有一份记录。
-- current_version 与新版本由同一事务更新，失败整体回滚，避免孤立复检记录或悬空版本。
CREATE TABLE sugar_quality_inspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT NOT NULL,
    batch_no      VARCHAR(32) NOT NULL COMMENT '批号快照',
    version       INT NOT NULL COMMENT '复检版本，从1开始',
    request_id    VARCHAR(36) NOT NULL COMMENT '幂等键：一次复检提交只落一条',
    inspected_at  DATETIME NOT NULL COMMENT '复检时间',
    brix          DECIMAL(5,2) NOT NULL COMMENT '复检糖度',
    purity        DECIMAL(5,2) NOT NULL COMMENT '复检纯度',
    fiber         DECIMAL(5,2) NOT NULL COMMENT '复检纤维分',
    conclusion    VARCHAR(16) NOT NULL COMMENT '通过/不通过',
    reject_reason VARCHAR(255) COMMENT '不通过原因，通过时为空',
    created_at    DATETIME NOT NULL,
    CONSTRAINT fk_sugar_inspection_batch FOREIGN KEY (batch_id) REFERENCES sugar_batch(id),
    CONSTRAINT uq_inspection_request UNIQUE (request_id),
    CONSTRAINT uq_inspection_version UNIQUE (batch_id, version),
    CONSTRAINT ck_inspection_version CHECK (version > 0),
    CONSTRAINT ck_inspection_metrics CHECK
      (brix BETWEEN 0 AND 100 AND purity BETWEEN 0 AND 100 AND fiber BETWEEN 0 AND 100),
    CONSTRAINT ck_inspection_conclusion CHECK
      ((conclusion = '通过' AND reject_reason IS NULL)
       OR (conclusion = '不通过' AND reject_reason IS NOT NULL AND CHAR_LENGTH(TRIM(reject_reason)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO cane_intake (ticket_no, supplier, truck_no, gross_kg, tare_kg, net_kg, deduction_kg, allocated_kg, brix, purity, fiber, result, status, intake_at) VALUES
('CI-2026001', '河东蔗农合作社', '桂A12345', 32000.00, 12000.00, 19600.00,   400.00, 14600.00, 13.80, 86.50, 12.40, '合格', '已验收', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
('CI-2026002', '河西甘蔗基地',   '桂B67890', 30500.00, 11800.00, 18000.00,   700.00, 17200.00, 12.90, 84.20, 13.10, '合格', '已验收', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
('CI-2026003', '南山散户李',     '桂C11111', 28800.00, 11500.00, 16500.00,   800.00,     0.00, 10.20, 79.80, 16.50, '拒收', '已拒收', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR),
('CI-2026004', '北岭合作社',     '桂D22222', 33200.00, 12100.00, 20300.00,   800.00,     0.00, 14.10, 88.10, 11.90, '合格', '已验收', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('CI-2026005', '新塘农场',       '桂E33333', 31000.00, 11900.00, 18200.00,   900.00,     0.00, 12.10, 82.40, 15.60, '扣杂', '待验',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
('CI-2026006', '双桥蔗区',       '桂F44444', 29900.00, 11700.00, 17400.00,   800.00,     0.00, 13.20, 85.60, 12.80, '合格', '待验',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 MINUTE);

INSERT INTO press_station (station_code, station_name, design_capacity_tph, current_load_tph, occupied_kg, status, last_maintain_at) VALUES
('PS-01', '1号压榨机组', 220.00, 198.00, 26800.00, '运行', UTC_TIMESTAMP() - INTERVAL 10 DAY),
('PS-02', '2号压榨机组', 200.00, 205.00,  5000.00, '运行', UTC_TIMESTAMP() - INTERVAL 25 DAY),
('PS-03', '3号压榨机组', 180.00,   0.00,      0.00, '停机', UTC_TIMESTAMP() - INTERVAL 70 DAY),
('PS-04', '4号压榨机组', 160.00,   0.00,      0.00, '检修', UTC_TIMESTAMP() - INTERVAL  3 DAY);

-- 投料批种子：与上面 allocated_kg / occupied_kg 严格对账
-- CI-2026001：9600（待投料→PS-01）+ 5000（投料中→PS-02）= 14600 已交接
-- CI-2026002：计划 18000 实际 17200（已完成→PS-01），少投 800 已释放，已交接 17200
-- CI-2026004：20300 已撤回，两侧余量已全额归还
-- PS-01 占用 = 9600 + 17200 = 26800；PS-02 占用 = 5000
INSERT INTO feed_batch (batch_no, request_id, intake_id, ticket_no, station_id, station_name, planned_kg, actual_kg, status, created_at, started_at, completed_at, cancelled_at) VALUES
('FB-2026001', 'seed-req-0001', 1, 'CI-2026001', 1, '1号压榨机组',  9600.00,     NULL, '待投料', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL, NULL, NULL),
('FB-2026002', 'seed-req-0002', 1, 'CI-2026001', 2, '2号压榨机组',  5000.00,     NULL, '投料中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL, NULL),
('FB-2026003', 'seed-req-0003', 2, 'CI-2026002', 1, '1号压榨机组', 18000.00, 17200.00, '已完成', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR - INTERVAL 30 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, NULL),
('FB-2026004', 'seed-req-0004', 4, 'CI-2026004', 2, '2号压榨机组', 20300.00,     NULL, '已撤回', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR - INTERVAL 30 MINUTE);

INSERT INTO evap_pan (pan_code, pan_name, pan_type, capacity_m3, status, last_wash_at) VALUES
('EP-01', '1号蒸发罐', '蒸发罐', 120.00, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('EP-02', '2号蒸发罐', '蒸发罐', 110.00, '洗罐', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
('EP-03', '3号蒸发罐', '蒸发罐', 100.00, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('CP-01', '1号结晶罐', '结晶罐',  80.00, '运行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('CP-02', '2号结晶罐', '结晶罐',  75.00, '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY);

-- 洗罐排程种子：EP-02 有一笔「清洗中」（罐状态必须正是「洗罐」，与排程一致）；
-- CP-01 有一笔待执行的洗罐窗口；另留一笔 EP-03 的历史已完成记录。
INSERT INTO pan_wash
  (wash_no, pan_id, pan_code, pan_name, pan_type, window_at, status, created_at, started_at, completed_at, cancelled_at)
VALUES
('PW-SEED-0001', 2, 'EP-02', '2号蒸发罐', '蒸发罐',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, '清洗中',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL, NULL),
('PW-SEED-0002', 4, 'CP-01', '1号结晶罐', '结晶罐',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 HOUR, '待执行',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL, NULL, NULL),
('PW-SEED-0003', 3, 'EP-03', '3号蒸发罐', '蒸发罐',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR, '已完成',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, NULL);

INSERT INTO sugar_batch (batch_no, grade, quantity_kg, brix, purity, fiber, warehouse, status, current_version, produced_at) VALUES
('SB-2026001', '白砂糖', 48000.00, 99.90, 99.70, 0.00, '1号糖仓', '已入库', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('SB-2026002', '白砂糖', 52000.00, 99.80, 99.60, 0.00, '1号糖仓', '已发运', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('SB-2026003', '赤砂糖', 31000.00, 97.50, 96.20, 0.00, '2号糖仓', '待复检', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('SB-2026004', '白砂糖', 45500.00, 99.85, 99.65, 0.00, '2号糖仓', '待复检', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('SB-2026005', '绵白糖', 28000.00, 99.10, 98.40, 0.00, '3号糖仓', '已入库', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR),
('SB-2026006', '赤砂糖', 26000.00, 96.90, 95.80, 0.10, '3号糖仓', '待复检', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR);

INSERT INTO sugar_quality_inspection
  (batch_id, batch_no, version, request_id, inspected_at, brix, purity, fiber, conclusion, reject_reason, created_at)
VALUES
(1, 'SB-2026001', 1, 'seed-inspection-0001', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 HOUR, 99.92, 99.72, 0.00, '通过', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 HOUR),
(2, 'SB-2026002', 1, 'seed-inspection-0002', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 HOUR, 99.84, 99.64, 0.00, '通过', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 HOUR),
(3, 'SB-2026003', 1, 'seed-inspection-0003', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 97.20, 95.90, 0.18, '不通过', '色值偏高且纤维分超标，需重新整理批次', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR),
(5, 'SB-2026005', 1, 'seed-inspection-0005', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR, 99.18, 98.52, 0.00, '通过', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR);
