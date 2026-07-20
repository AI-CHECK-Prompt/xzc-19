-- =====================================================================
-- 跨境冷链 GxP 合规系统 - 数据库 Schema
-- 目标：药监局飞行检查（GSP / GxP / WHO PQS）
-- 存储策略：5年不可篡改（WORM + 哈希链 + RSA 签名）
-- =====================================================================

-- ===== 基础实体 =====
CREATE TABLE IF NOT EXISTS drug_enterprise (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    license_no      VARCHAR(100) NOT NULL UNIQUE,
    country         VARCHAR(50),
    contact         VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS drug_batch (
    id                  BIGSERIAL PRIMARY KEY,
    batch_no            VARCHAR(100) NOT NULL UNIQUE,
    product_name        VARCHAR(200) NOT NULL,
    dosage_form         VARCHAR(50)  NOT NULL,   -- 剂型: COLD(2-8C) / FROZEN(-20C) / NORMAL(15-25C)
    specification       VARCHAR(100),
    quantity            INTEGER NOT NULL,
    manufacturer        VARCHAR(200),
    production_date     DATE,
    expiry_date         DATE,
    enterprise_id       BIGINT REFERENCES drug_enterprise(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_drug_batch_form ON drug_batch(dosage_form);

CREATE TABLE IF NOT EXISTS transport_task (
    id                  BIGSERIAL PRIMARY KEY,
    task_no             VARCHAR(100) NOT NULL UNIQUE,
    origin              VARCHAR(200),
    destination         VARCHAR(200),
    origin_country      VARCHAR(50),
    dest_country        VARCHAR(50),
    departure_at        TIMESTAMPTZ,
    arrival_at          TIMESTAMPTZ,
    expected_arrival_at TIMESTAMPTZ,
    status              VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    driver_name         VARCHAR(100),
    vehicle_no          VARCHAR(50),
    enterprise_id       BIGINT REFERENCES drug_enterprise(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_task_status ON transport_task(status);
CREATE INDEX IF NOT EXISTS idx_task_departure ON transport_task(departure_at);

CREATE TABLE IF NOT EXISTS task_batch_rel (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT NOT NULL REFERENCES transport_task(id),
    batch_id        BIGINT NOT NULL REFERENCES drug_batch(id),
    quantity        INTEGER NOT NULL,
    UNIQUE(task_id, batch_id)
);

CREATE TABLE IF NOT EXISTS temp_recorder (
    id              BIGSERIAL PRIMARY KEY,
    device_no       VARCHAR(100) NOT NULL UNIQUE,
    model           VARCHAR(100),
    vendor          VARCHAR(100),
    sample_interval_sec INT NOT NULL DEFAULT 60,
    clock_skew_ms   BIGINT NOT NULL DEFAULT 0,    -- 设备时钟漂移
    public_key      TEXT,                          -- 设备公钥（用于验证签名）
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 温控采样点（时序数据 + WORM + 哈希链 + RSA 签名）=====
CREATE TABLE IF NOT EXISTS temp_sample (
    id                  BIGSERIAL PRIMARY KEY,
    device_no           VARCHAR(100) NOT NULL,
    task_id             BIGINT REFERENCES transport_task(id),
    sample_at           TIMESTAMPTZ NOT NULL,       -- 设备侧采样时间（已校准）
    server_received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    temperature         NUMERIC(6,3) NOT NULL,      -- 摄氏度
    humidity            NUMERIC(5,2),                -- 百分比
    door_open           BOOLEAN NOT NULL DEFAULT FALSE,
    latitude            NUMERIC(10,6),
    longitude           NUMERIC(10,6),
    driver_event        VARCHAR(50),                 -- 司机操作事件
    payload_hash        VARCHAR(128) NOT NULL,       -- 当前点 SHA-256
    prev_hash           VARCHAR(128),                -- 前一点哈希（哈希链）
    signature           TEXT NOT NULL,               -- RSA 签名（payload_hash + prev_hash + sample_at）
    seq_no              BIGINT NOT NULL,             -- 设备内递增序号
    UNIQUE(device_no, seq_no)
);
-- 时序索引
CREATE INDEX IF NOT EXISTS idx_temp_task_time ON temp_sample(task_id, sample_at);
CREATE INDEX IF NOT EXISTS idx_temp_device_time ON temp_sample(device_no, sample_at);
CREATE INDEX IF NOT EXISTS idx_temp_received ON temp_sample(server_received_at);

-- 时序超表（TimescaleDB 可选；不存在则降级为普通表）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb') THEN
        PERFORM create_hypertable('temp_sample', 'sample_at', if_not_exists => TRUE, chunk_time_interval => INTERVAL '7 days');
    END IF;
END$$;

-- ===== 轨迹点 =====
CREATE TABLE IF NOT EXISTS track_point (
    id                  BIGSERIAL PRIMARY KEY,
    device_no           VARCHAR(100) NOT NULL,
    task_id             BIGINT REFERENCES transport_task(id),
    sample_at           TIMESTAMPTZ NOT NULL,
    latitude            NUMERIC(10,6) NOT NULL,
    longitude           NUMERIC(10,6) NOT NULL,
    speed_kmh           NUMERIC(6,2),
    heading             NUMERIC(6,2),
    payload_hash        VARCHAR(128) NOT NULL,
    prev_hash           VARCHAR(128),
    signature           TEXT NOT NULL,
    seq_no              BIGINT NOT NULL,
    UNIQUE(device_no, seq_no)
);
CREATE INDEX IF NOT EXISTS idx_track_task_time ON track_point(task_id, sample_at);
CREATE INDEX IF NOT EXISTS idx_track_device_time ON track_point(device_no, sample_at);

-- ===== 海关报关单（多语言结构化解析）=====
CREATE TABLE IF NOT EXISTS customs_declaration (
    id                  BIGSERIAL PRIMARY KEY,
    decl_no             VARCHAR(100) NOT NULL UNIQUE,
    task_id             BIGINT REFERENCES transport_task(id),
    language            VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
    raw_text            TEXT,
    parsed_json         TEXT,                       -- 结构化结果（JSON 字符串）
    consignor           VARCHAR(200),
    consignee           VARCHAR(200),
    decl_date           TIMESTAMPTZ,
    total_value         NUMERIC(15,2),
    currency            VARCHAR(10),
    status              VARCHAR(20) NOT NULL DEFAULT 'PARSED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 报关单内药品批号（用于批号匹配校验）
CREATE TABLE IF NOT EXISTS customs_batch_item (
    id                  BIGSERIAL PRIMARY KEY,
    declaration_id      BIGINT NOT NULL REFERENCES customs_declaration(id) ON DELETE CASCADE,
    declared_batch_no   VARCHAR(100) NOT NULL,     -- 报关单上声明的批号
    declared_qty        INTEGER,
    declared_product    VARCHAR(200),
    matched_batch_id    BIGINT REFERENCES drug_batch(id),
    match_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- MATCHED / MISMATCH / MISSING
    match_detail        TEXT
);
CREATE INDEX IF NOT EXISTS idx_customs_batch_no ON customs_batch_item(declared_batch_no);

-- ===== 检验报告 =====
CREATE TABLE IF NOT EXISTS inspection_report (
    id                  BIGSERIAL PRIMARY KEY,
    report_no           VARCHAR(100) NOT NULL UNIQUE,
    task_id             BIGINT REFERENCES transport_task(id),
    batch_id            BIGINT REFERENCES drug_batch(id),
    inspector           VARCHAR(100),
    inspected_at        TIMESTAMPTZ,
    conclusion          VARCHAR(30),                -- PASS / FAIL / CONDITIONAL
    raw_text            TEXT,
    parsed_json         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 合规规则集 =====
CREATE TABLE IF NOT EXISTS compliance_rule (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(100) NOT NULL UNIQUE,  -- RULE_CONTINUITY / RULE_CUMULATIVE_DEVIATION ...
    name            VARCHAR(200) NOT NULL,
    category        VARCHAR(50) NOT NULL,           -- CONTINUITY / CUMULATIVE / RANGE / DOOR / TRACK / SMOOTH
    dosage_form     VARCHAR(50),                    -- NULL=通用, 否则只针对该剂型
    severity        VARCHAR(20) NOT NULL DEFAULT 'MAJOR',  -- CRITICAL / MAJOR / MINOR
    action          VARCHAR(20) NOT NULL DEFAULT 'BLOCK',  -- BLOCK / REVIEW
    expression      TEXT NOT NULL,                  -- DSL 表达式或参数化 JSON
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 审计报告（任务级）=====
CREATE TABLE IF NOT EXISTS audit_report (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING',  -- RUNNING / PASS / BLOCK / REVIEW
    rule_version        INTEGER NOT NULL DEFAULT 1,
    finding_count       INTEGER NOT NULL DEFAULT 0,
    summary             TEXT,                                -- JSON: 摘要
    payload             TEXT,                                -- JSON: 完整证据链
    payload_hash        VARCHAR(128),
    signature           TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_task ON audit_report(task_id);

-- ===== 审计证据（每条不合规项）=====
CREATE TABLE IF NOT EXISTS audit_finding (
    id                  BIGSERIAL PRIMARY KEY,
    audit_id            BIGINT NOT NULL REFERENCES audit_report(id) ON DELETE CASCADE,
    rule_code           VARCHAR(100) NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    action              VARCHAR(20) NOT NULL,
    time_range_start    TIMESTAMPTZ,
    time_range_end      TIMESTAMPTZ,
    affected_batch_ids  TEXT,                          -- 受影响药品批号（JSON 数组）
    affected_qty        INTEGER,
    temperature_min     NUMERIC(6,3),
    temperature_max     NUMERIC(6,3),
    description         TEXT NOT NULL,                 -- 详细描述
    evidence            TEXT NOT NULL,                 -- 证据 JSON（采样点 / 轨迹点 / 哈希）
    payload_hash        VARCHAR(128),
    signature           TEXT
);
CREATE INDEX IF NOT EXISTS idx_finding_audit ON audit_finding(audit_id);
CREATE INDEX IF NOT EXISTS idx_finding_severity ON audit_finding(severity);

-- ===== 放行/拦截决策单 =====
CREATE TABLE IF NOT EXISTS release_decision (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    decision            VARCHAR(20) NOT NULL,          -- RELEASE / BLOCK / CONDITIONAL_RELEASE
    decided_by          VARCHAR(100) NOT NULL,
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    basis               TEXT,                          -- 决策依据 JSON
    audit_report_id     BIGINT REFERENCES audit_report(id),
    customs_ok          BOOLEAN NOT NULL DEFAULT FALSE,
    inspection_ok       BOOLEAN NOT NULL DEFAULT FALSE,
    temperature_ok      BOOLEAN NOT NULL DEFAULT FALSE,
    comment             TEXT,
    payload_hash        VARCHAR(128),
    signature           TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_release_task ON release_decision(task_id);

-- ===== 操作日志（GxP 全留痕）=====
CREATE TABLE IF NOT EXISTS operation_log (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(100) NOT NULL,
    user_role           VARCHAR(50) NOT NULL,          -- DISPATCHER / AUDITOR / QA / CUSTOMS
    action              VARCHAR(100) NOT NULL,
    resource_type       VARCHAR(50),
    resource_id         VARCHAR(100),
    ip                  VARCHAR(50),
    user_agent          VARCHAR(500),
    payload             TEXT,
    result              VARCHAR(20),
    payload_hash        VARCHAR(128),
    prev_hash           VARCHAR(128),
    signature           TEXT,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_oplog_user ON operation_log(user_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_oplog_resource ON operation_log(resource_type, resource_id);

-- ===== 哈希链锚点（每 N 条操作日志一个锚点，便于校验）=====
CREATE TABLE IF NOT EXISTS hash_anchor (
    id                  BIGSERIAL PRIMARY KEY,
    last_log_id         BIGINT NOT NULL,
    chain_root          VARCHAR(128) NOT NULL,         -- 本锚点覆盖范围的根哈希
    prev_anchor_hash    VARCHAR(128),
    anchor_hash         VARCHAR(128) NOT NULL,
    signature           TEXT NOT NULL,
    count               INTEGER NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 用户/角色 =====
CREATE TABLE IF NOT EXISTS app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL UNIQUE,
    password_hash   VARCHAR(200) NOT NULL,
    full_name       VARCHAR(100),
    role            VARCHAR(50) NOT NULL,            -- DISPATCHER / AUDITOR / QA / CUSTOMS / ADMIN
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 飞检导出记录 =====
CREATE TABLE IF NOT EXISTS export_record (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT REFERENCES transport_task(id),
    format          VARCHAR(10) NOT NULL,           -- XML / PDF / EXCEL
    file_path       VARCHAR(500) NOT NULL,
    file_hash       VARCHAR(128) NOT NULL,
    signature       TEXT NOT NULL,
    requested_by    VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================================
-- WORM 强制：禁止对核心不可篡改表的 UPDATE/DELETE
-- 使用触发器实现
-- =====================================================================
CREATE OR REPLACE FUNCTION enforce_worm() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'WORM enforcement: % on table % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'worm_no_update') THEN
        CREATE TRIGGER worm_no_update BEFORE UPDATE ON temp_sample
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_delete BEFORE DELETE ON temp_sample
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_update BEFORE UPDATE ON track_point
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_delete BEFORE DELETE ON track_point
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_update BEFORE UPDATE ON operation_log
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_delete BEFORE DELETE ON operation_log
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_update BEFORE UPDATE ON audit_finding
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
        CREATE TRIGGER worm_no_delete BEFORE DELETE ON audit_finding
            FOR EACH ROW EXECUTE FUNCTION enforce_worm();
    END IF;
END$$;
