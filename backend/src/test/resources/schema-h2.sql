-- H2（MODE=MySQL）测试库结构：与 MySQL schema.sql 同构，保留闭环测试涉及的
-- 进厂磅单、压榨机组、洗罐、成品糖批次、质量复检表；唯一索引和 CHECK 约束在数据库层兜底。

DROP TABLE IF EXISTS feed_batch;
DROP TABLE IF EXISTS cane_intake;
DROP TABLE IF EXISTS sugar_quality_inspection;
DROP TABLE IF EXISTS sugar_batch;
DROP TABLE IF EXISTS pan_wash;
DROP TABLE IF EXISTS evap_pan;
DROP TABLE IF EXISTS press_station;

CREATE TABLE cane_intake (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no     VARCHAR(32) NOT NULL UNIQUE,
    supplier      VARCHAR(64) NOT NULL,
    truck_no      VARCHAR(24) NOT NULL,
    gross_kg      DECIMAL(12,2) NOT NULL,
    tare_kg       DECIMAL(12,2) NOT NULL,
    net_kg        DECIMAL(12,2) NOT NULL,
    deduction_kg  DECIMAL(12,2) NOT NULL DEFAULT 0,
    allocated_kg  DECIMAL(12,2) NOT NULL DEFAULT 0,
    brix          DECIMAL(5,2) NOT NULL,
    purity        DECIMAL(5,2) NOT NULL,
    fiber         DECIMAL(5,2) NOT NULL,
    result        VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    intake_at     TIMESTAMP,
    CONSTRAINT ck_intake_alloc CHECK (allocated_kg >= 0 AND allocated_kg <= net_kg)
);

CREATE TABLE sugar_batch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no        VARCHAR(32) NOT NULL UNIQUE,
    grade           VARCHAR(16) NOT NULL,
    quantity_kg     DECIMAL(12,2) NOT NULL,
    brix            DECIMAL(5,2) NOT NULL,
    purity          DECIMAL(5,2) NOT NULL,
    fiber           DECIMAL(5,2) NOT NULL,
    warehouse       VARCHAR(24) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    current_version INT NOT NULL DEFAULT 0,
    produced_at     TIMESTAMP,
    CONSTRAINT ck_sugar_status CHECK (status IN ('待复检', '已入库', '已发运')),
    CONSTRAINT ck_sugar_current_version CHECK (current_version >= 0)
);

CREATE TABLE sugar_quality_inspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT NOT NULL,
    batch_no      VARCHAR(32) NOT NULL,
    version       INT NOT NULL,
    request_id    VARCHAR(36) NOT NULL,
    inspected_at  TIMESTAMP NOT NULL,
    brix          DECIMAL(5,2) NOT NULL,
    purity        DECIMAL(5,2) NOT NULL,
    fiber         DECIMAL(5,2) NOT NULL,
    conclusion    VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(255),
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT fk_sugar_inspection_batch FOREIGN KEY (batch_id) REFERENCES sugar_batch(id),
    CONSTRAINT uq_inspection_request UNIQUE (request_id),
    CONSTRAINT uq_inspection_version UNIQUE (batch_id, version),
    CONSTRAINT ck_inspection_version CHECK (version > 0),
    CONSTRAINT ck_inspection_metrics CHECK
      (brix BETWEEN 0 AND 100 AND purity BETWEEN 0 AND 100 AND fiber BETWEEN 0 AND 100),
    CONSTRAINT ck_inspection_conclusion CHECK
      ((conclusion = '通过' AND reject_reason IS NULL)
       OR (conclusion = '不通过' AND reject_reason IS NOT NULL AND CHAR_LENGTH(TRIM(reject_reason)) > 0))
);

CREATE TABLE press_station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code        VARCHAR(24) NOT NULL UNIQUE,
    station_name        VARCHAR(64) NOT NULL,
    design_capacity_tph DECIMAL(8,2) NOT NULL,
    current_load_tph    DECIMAL(8,2) NOT NULL DEFAULT 0,
    occupied_kg         DECIMAL(14,2) NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL,
    last_maintain_at    TIMESTAMP,
    last_request_id     VARCHAR(36),
    CONSTRAINT ck_press_occupied CHECK (occupied_kg >= 0 AND occupied_kg <= design_capacity_tph * 1000),
    CONSTRAINT ck_press_maintain CHECK
      (status <> '检修' OR (current_load_tph = 0 AND last_maintain_at IS NOT NULL))
);

CREATE TABLE evap_pan (    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    pan_code     VARCHAR(24) NOT NULL UNIQUE,
    pan_name     VARCHAR(64) NOT NULL,
    pan_type     VARCHAR(16) NOT NULL,
    capacity_m3  DECIMAL(8,2) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    last_wash_at TIMESTAMP
);

CREATE TABLE pan_wash (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    wash_no       VARCHAR(32) NOT NULL UNIQUE,
    pan_id        BIGINT NOT NULL,
    pan_code      VARCHAR(24) NOT NULL,
    pan_name      VARCHAR(64) NOT NULL,
    pan_type      VARCHAR(16) NOT NULL,
    window_at     TIMESTAMP NOT NULL,
    status        VARCHAR(16) NOT NULL,
    created_at    TIMESTAMP,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    failed_reason VARCHAR(255),
    failed_at     TIMESTAMP,
    active_pan_id BIGINT GENERATED ALWAYS AS
                    (CASE WHEN status IN ('待执行', '清洗中') THEN pan_id ELSE NULL END),
    CONSTRAINT fk_wash_pan FOREIGN KEY (pan_id) REFERENCES evap_pan(id),
    CONSTRAINT ck_wash_status CHECK
      ((status = '已完成' AND completed_at IS NOT NULL AND started_at IS NOT NULL)
       OR (status = '清洗中' AND started_at IS NOT NULL AND completed_at IS NULL)
       OR (status = '待执行' AND started_at IS NULL AND completed_at IS NULL)
       OR (status = '已取消' AND cancelled_at IS NOT NULL)),
    CONSTRAINT uq_active_pan UNIQUE (active_pan_id)
);
