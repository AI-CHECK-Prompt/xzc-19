-- =====================================================================
-- 多式联运全链路协同 - 数据库 Schema 扩展
-- V3：在 V1 基础上扩展运输段、段间交接、承运商、异常处置预案、
--     影响剂量估算、责任段判定、整改工单、多链合规结论、监管报告
-- 重点：段间交接的"非在途段"（如港口堆场停放）显式标记，WORM 不变
-- =====================================================================

-- ===== 多承运商档案 =====
CREATE TABLE IF NOT EXISTS carrier (
    id              BIGSERIAL PRIMARY KEY,
    carrier_code    VARCHAR(50) NOT NULL UNIQUE,    -- 承运商编码
    carrier_name    VARCHAR(200) NOT NULL,
    carrier_type    VARCHAR(30) NOT NULL,           -- ROAD / SEA / AIR / RAIL / STORAGE
    license_no      VARCHAR(100),
    country         VARCHAR(50),
    contact_name    VARCHAR(100),
    contact_phone   VARCHAR(50),
    sla_score       NUMERIC(4,2) DEFAULT 95.0,      -- SLA 评分
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 运输段（一个订单可拆 N 段）=====
CREATE TABLE IF NOT EXISTS transport_segment (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    segment_no          VARCHAR(20) NOT NULL,        -- 段序号：S1/S2/S3...
    segment_type        VARCHAR(20) NOT NULL,        -- ROAD / SEA / AIR / RAIL / STORAGE（堆场/中转）
    origin              VARCHAR(200) NOT NULL,
    destination         VARCHAR(200) NOT NULL,
    carrier_id          BIGINT REFERENCES carrier(id),
    transport_tool      VARCHAR(100),                -- 车辆/船次/航班号
    tool_no             VARCHAR(100),
    temperature_min     NUMERIC(6,3),                -- 温控要求下限
    temperature_max     NUMERIC(6,3),                -- 温控要求上限
    planned_depart_at   TIMESTAMPTZ,
    planned_arrive_at   TIMESTAMPTZ,
    actual_depart_at    TIMESTAMPTZ,
    actual_arrive_at    TIMESTAMPTZ,
    is_in_transit       BOOLEAN NOT NULL DEFAULT TRUE,  -- FALSE=非在途段（堆场/中转停放）
    status              VARCHAR(20) NOT NULL DEFAULT 'PLANNED',  -- PLANNED/DEPARTED/ARRIVED/HANDED_OVER
    responsible_person  VARCHAR(100),                -- 责任人
    responsible_phone   VARCHAR(50),
    remark              TEXT,
    seq                 INTEGER NOT NULL,             -- 段在订单内的顺序
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(task_id, segment_no)
);
CREATE INDEX IF NOT EXISTS idx_segment_task ON transport_segment(task_id);
CREATE INDEX IF NOT EXISTS idx_segment_carrier ON transport_segment(carrier_id);
CREATE INDEX IF NOT EXISTS idx_segment_in_transit ON transport_segment(is_in_transit);

-- ===== 段间交接记录（前后两段温控/轨迹/设备无缝衔接）=====
CREATE TABLE IF NOT EXISTS segment_handover (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    from_segment_id     BIGINT NOT NULL REFERENCES transport_segment(id),
    to_segment_id       BIGINT NOT NULL REFERENCES transport_segment(id),
    handover_at         TIMESTAMPTZ NOT NULL,
    from_carrier_id     BIGINT REFERENCES carrier(id),
    to_carrier_id       BIGINT REFERENCES carrier(id),
    last_temp_c         NUMERIC(6,3),                -- 交接时前段最后温度
    last_humidity       NUMERIC(5,2),
    last_device_no      VARCHAR(100),                -- 交接的设备编号
    continuity_ok       BOOLEAN NOT NULL DEFAULT TRUE,  -- 温度是否连续
    device_handoff_ok   BOOLEAN NOT NULL DEFAULT TRUE,  -- 设备是否完成交接
    gap_minutes         INTEGER,                      -- 两段时间断点
    is_non_transit      BOOLEAN NOT NULL DEFAULT FALSE, -- 是否非在途段（如堆场停放）
    storage_location    VARCHAR(200),                 -- 堆场/中转位置
    storage_temperature NUMERIC(6,3),                 -- 堆场温度
    evidence_json       TEXT,                         -- 交接证据 JSON
    operator            VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_handover_task ON segment_handover(task_id);
CREATE INDEX IF NOT EXISTS idx_handover_from ON segment_handover(from_segment_id);
CREATE INDEX IF NOT EXISTS idx_handover_to ON segment_handover(to_segment_id);

-- ===== 异常处置预案库（按剂型+异常类型索引）=====
CREATE TABLE IF NOT EXISTS exception_prescription (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(100) NOT NULL UNIQUE,
    dosage_form         VARCHAR(50) NOT NULL,        -- COLD/FROZEN/NORMAL
    exception_type      VARCHAR(50) NOT NULL,        -- OVERHEAT / UNDERCOOL / DOOR_OPEN / TRACK_DEVIATION / DEVICE_OFFLINE / SAMPLING_GAP
    title               VARCHAR(200) NOT NULL,
    threshold_json      TEXT NOT NULL,               -- 判定阈值 JSON
    impact_rule_json    TEXT,                        -- 影响剂量估算规则
    actions_json        TEXT NOT NULL,               -- 建议动作 JSON 数组
    responsible_party   VARCHAR(50) NOT NULL,        -- CARRIER / ENTERPRISE / SUPPLIER
    response_hours      INTEGER NOT NULL DEFAULT 24, -- 处置时效
    regulatory_report   BOOLEAN NOT NULL DEFAULT FALSE, -- 是否触发监管报告
    severity            VARCHAR(20) NOT NULL DEFAULT 'MAJOR',  -- CRITICAL/MAJOR/MINOR
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    version             INTEGER NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_prescription_form ON exception_prescription(dosage_form, exception_type);

-- ===== 影响剂量估算规则 =====
CREATE TABLE IF NOT EXISTS dose_impact_rule (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(100) NOT NULL UNIQUE,
    dosage_form         VARCHAR(50) NOT NULL,
    exception_type      VARCHAR(50) NOT NULL,
    base_factor         NUMERIC(6,4) NOT NULL,        -- 基础系数
    per_minute_factor   NUMERIC(6,4) NOT NULL DEFAULT 0, -- 每分钟系数
    per_degree_factor   NUMERIC(6,4) NOT NULL DEFAULT 0, -- 每℃偏离系数
    formula             TEXT,                          -- 公式说明
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 责任段判定规则 =====
CREATE TABLE IF NOT EXISTS responsibility_rule (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(100) NOT NULL UNIQUE,
    exception_type      VARCHAR(50) NOT NULL,
    rule_expr           TEXT NOT NULL,                -- 规则表达式
    default_party       VARCHAR(50) NOT NULL,         -- 默认责任方
    priority            INTEGER NOT NULL DEFAULT 100,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ===== 承运商整改工单 =====
CREATE TABLE IF NOT EXISTS carrier_workorder (
    id                  BIGSERIAL PRIMARY KEY,
    workorder_no        VARCHAR(50) NOT NULL UNIQUE,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    segment_id          BIGINT NOT NULL REFERENCES transport_segment(id),
    carrier_id          BIGINT NOT NULL REFERENCES carrier(id),
    exception_type      VARCHAR(50) NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    title               VARCHAR(300) NOT NULL,
    description         TEXT,
    prescription_id     BIGINT REFERENCES exception_prescription(id),
    affected_qty        INTEGER,
    responsible_party   VARCHAR(50),
    response_deadline   TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN/IN_PROGRESS/RESOLVED/CLOSED/OVERDUE
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    resolved_note       TEXT
);
CREATE INDEX IF NOT EXISTS idx_workorder_task ON carrier_workorder(task_id);
CREATE INDEX IF NOT EXISTS idx_workorder_segment ON carrier_workorder(segment_id);
CREATE INDEX IF NOT EXISTS idx_workorder_carrier ON carrier_workorder(carrier_id);
CREATE INDEX IF NOT EXISTS idx_workorder_status ON carrier_workorder(status);

-- ===== 段-审计证据关联（按段归档 finding）=====
CREATE TABLE IF NOT EXISTS segment_finding_rel (
    id                  BIGSERIAL PRIMARY KEY,
    segment_id          BIGINT NOT NULL REFERENCES transport_segment(id),
    finding_id          BIGINT NOT NULL REFERENCES audit_finding(id),
    affected_qty        INTEGER,
    contribution_score  NUMERIC(6,4),                 -- 对全链结论的贡献度 0~1
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_segfinding_segment ON segment_finding_rel(segment_id);
CREATE INDEX IF NOT EXISTS idx_segfinding_finding ON segment_finding_rel(finding_id);

-- ===== 多式联运全链合规结论（按段聚合）=====
CREATE TABLE IF NOT EXISTS multi_chain_compliance (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    overall_status      VARCHAR(20) NOT NULL,         -- PASS / REVIEW / BLOCK
    overall_decision    VARCHAR(30) NOT NULL,         -- RELEASE / CONDITIONAL_RELEASE / BLOCK
    segment_count       INTEGER NOT NULL,
    critical_segments   TEXT,                         -- 关键段（导致 BLOCK/REVIEW 的段）JSON
    contribution_json   TEXT,                         -- 每段贡献 JSON
    audit_id            BIGINT REFERENCES audit_report(id),
    summary_text        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(task_id)
);

-- ===== 监管报告（自动生成）=====
CREATE TABLE IF NOT EXISTS regulatory_report (
    id                  BIGSERIAL PRIMARY KEY,
    report_no           VARCHAR(50) NOT NULL UNIQUE,
    task_id             BIGINT NOT NULL REFERENCES transport_task(id),
    report_type         VARCHAR(30) NOT NULL,         -- INCIDENT / SUMMARY / EXCEPTION
    title               VARCHAR(300),
    body_text           TEXT,                         -- 报告正文
    body_json           TEXT,                         -- 结构化 JSON
    triggered_by        VARCHAR(50),                  -- 触发的异常类型
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/SUBMITTED/ACKNOWLEDGED
    submitted_at        TIMESTAMPTZ,
    payload_hash        VARCHAR(128),
    signature           TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_regreport_task ON regulatory_report(task_id);
