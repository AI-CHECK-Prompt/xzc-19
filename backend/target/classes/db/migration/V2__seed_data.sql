-- 种子数据：默认合规规则集、用户、剂型→温控曲线

-- ===== 默认用户（密码均为 password123，BCrypt）=====
-- BCrypt: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
INSERT INTO app_user (username, password_hash, full_name, role) VALUES
    ('dispatcher', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '李调度', 'DISPATCHER'),
    ('auditor',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '王审计', 'AUDITOR'),
    ('qa',         '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '张质量', 'QA'),
    ('customs',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '赵海关', 'CUSTOMS'),
    ('admin',      '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '管理员', 'ADMIN')
ON CONFLICT (username) DO NOTHING;

-- ===== 默认合规规则（6 类审计）=====
INSERT INTO compliance_rule (code, name, category, dosage_form, severity, action, expression, description) VALUES
    -- 1. 温度连续性审计
    ('RULE_CONTINUITY_COLD', '2-8℃冷藏温度连续性', 'CONTINUITY', 'COLD', 'CRITICAL', 'BLOCK',
     '{"max_gap_sec": 300}',
     '检测温控采样点是否存在超过 5 分钟的连续中断'),

    -- 2. 累积偏差审计（MKT / 累积超出时间）
    ('RULE_CUMULATIVE_COLD', '2-8℃冷藏累积偏差', 'CUMULATIVE', 'COLD', 'MAJOR', 'REVIEW',
     '{"max_above_minutes": 30, "max_below_minutes": 30}',
     '检测温度在 8℃ 以上或 2℃ 以下持续累计时长'),

    -- 3. 最小/最大值越界审计
    ('RULE_RANGE_COLD', '2-8℃冷藏瞬时越界', 'RANGE', 'COLD', 'CRITICAL', 'BLOCK',
     '{"min_c": 2.0, "max_c": 8.0, "max_breach_minutes": 5}',
     '检测单点越界与持续越界时长'),

    ('RULE_RANGE_FROZEN', '-20℃以下冷冻瞬时越界', 'RANGE', 'FROZEN', 'CRITICAL', 'BLOCK',
     '{"min_c": -25.0, "max_c": -15.0, "max_breach_minutes": 10}',

     '冷冻温度区间 -25℃ ~ -15℃'),

    ('RULE_RANGE_NORMAL', '15-25℃常温瞬时越界', 'RANGE', 'NORMAL', 'MAJOR', 'REVIEW',
     '{"min_c": 15.0, "max_c": 25.0, "max_breach_minutes": 30}',

     '常温区间 15-25℃'),

    -- 4. 门开关合理性审计
    ('RULE_DOOR', '门开关合理性审计', 'DOOR', NULL, 'MAJOR', 'REVIEW',
     '{"max_open_count_per_hour": 4, "max_open_duration_sec": 180}',
     '运输途中门开关频次与单次时长'),

    -- 5. 轨迹偏移审计
    ('RULE_TRACK', '运输轨迹偏移审计', 'TRACK', NULL, 'MAJOR', 'REVIEW',
     '{"max_deviation_km": 50.0}',
     '实际轨迹与计划路线的最大偏移距离'),

    -- 6. 温度曲线平滑性审计
    ('RULE_SMOOTH', '温度曲线平滑性审计', 'SMOOTH', NULL, 'MINOR', 'REVIEW',
     '{"max_rate_per_min": 2.0, "min_sample_count": 5}',
     '相邻采样点的温度变化速率，防止异常跳变（潜在篡改）')
ON CONFLICT (code) DO NOTHING;
