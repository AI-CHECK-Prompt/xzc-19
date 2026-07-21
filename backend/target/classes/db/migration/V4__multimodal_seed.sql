-- =====================================================================
-- 多式联运扩展 - 种子数据
-- 包含：3 家承运商（陆运/海运/仓储）、6 类异常 × 3 剂型 处置预案、
--       影响剂量估算规则、责任段判定规则
-- =====================================================================

-- ===== 3 家承运商 =====
INSERT INTO carrier (carrier_code, carrier_name, carrier_type, license_no, country, contact_name, contact_phone, sla_score) VALUES
    ('CARRIER-ROAD-001', '北方冷链陆运有限公司', 'ROAD',     'RD-2026-0001', 'CN', '王经理', '13800001001', 96.5),
    ('CARRIER-SEA-002',  '中远海运冷链事业部',   'SEA',      'SEA-2026-0002','CN', '李船长', '13800001002', 94.8),
    ('CARRIER-WH-003',   '鹿特丹海外仓-RDM',     'STORAGE',  'WH-NL-2026-03','NL', 'Jan v.d.Berg', '+31-10-1234567', 98.0)
ON CONFLICT (carrier_code) DO NOTHING;

-- ===== 异常处置预案（剂型 × 异常类型）=====
-- COLD 剂型预案
INSERT INTO exception_prescription
    (code, dosage_form, exception_type, title, threshold_json, impact_rule_json, actions_json,
     responsible_party, response_hours, regulatory_report, severity) VALUES
    ('RX_COLD_OVERHEAT', 'COLD', 'OVERHEAT', '冷藏剂型温度越上限',
     '{"min_c":2.0,"max_c":8.0,"sustained_minutes":5}',
     '{"formula":"dose = base_factor * (peak - max_c) * (duration_min + 1)","base_factor":0.05,"per_minute_factor":0.02}',
     '["立即转移至备用冷藏设备","启动冷机强冷","通知 QA 评估是否召回","给责任段承运商开具整改工单"]',
     'CARRIER', 4, TRUE, 'CRITICAL'),

    ('RX_COLD_UNDERCOOL', 'COLD', 'UNDERCOOL', '冷藏剂型温度越下限',
     '{"min_c":2.0,"max_c":8.0,"sustained_minutes":10}',
     '{"formula":"dose = base_factor * (min_c - valley) * (duration_min + 1)","base_factor":0.03,"per_minute_factor":0.01}',
     '["检查冷机运行","提升目标温度至区间中值","评估冻结风险"]',
     'CARRIER', 8, FALSE, 'MAJOR'),

    ('RX_COLD_DOOR', 'COLD', 'DOOR_OPEN', '冷藏剂型门开启',
     '{"max_open_count_per_hour":4,"max_open_duration_sec":180}',
     '{"formula":"dose = base_factor * (open_minutes * per_minute_factor)","base_factor":0.04,"per_minute_factor":0.05}',
     '["复核装卸流程","检查门封条","驾驶员培训"]',
     'CARRIER', 24, FALSE, 'MAJOR'),

    ('RX_COLD_TRACK', 'COLD', 'TRACK_DEVIATION', '运输轨迹偏移',
     '{"max_deviation_km":50.0}',
     '{"formula":"dose = base_factor * (deviation_km - threshold)","base_factor":0.02,"per_km_factor":0.001}',
     '["核实偏离原因","复核路线计划"]',
     'CARRIER', 24, FALSE, 'MINOR'),

    ('RX_COLD_DEVICE', 'COLD', 'DEVICE_OFFLINE', '设备断电/离线',
     '{"max_offline_minutes":10}',
     '{"formula":"dose = base_factor * offline_minutes","base_factor":0.1,"per_minute_factor":0.03}',
     '["更换备用记录仪","追溯设备故障","承运商整改"]',
     'CARRIER', 4, TRUE, 'CRITICAL'),

    ('RX_COLD_SAMPLING', 'COLD', 'SAMPLING_GAP', '采样中断',
     '{"max_gap_minutes":5}',
     '{"formula":"dose = base_factor * (gap_minutes - threshold)","base_factor":0.02,"per_minute_factor":0.005}',
     '["补采或重传","检查设备通信"]',
     'CARRIER', 12, FALSE, 'MAJOR'),

    -- FROZEN 剂型预案
    ('RX_FROZEN_OVERHEAT', 'FROZEN', 'OVERHEAT', '冷冻剂型温度越上限',
     '{"min_c":-25.0,"max_c":-15.0,"sustained_minutes":10}',
     '{"formula":"dose = base_factor * (peak - max_c) * (duration_min + 1)","base_factor":0.10,"per_minute_factor":0.05}',
     '["立即评估活性损失","可能需要召回","上报监管"]',
     'CARRIER', 2, TRUE, 'CRITICAL'),

    ('RX_FROZEN_DOOR', 'FROZEN', 'DOOR_OPEN', '冷冻剂型门开启',
     '{"max_open_duration_sec":120}',
     '{"formula":"dose = base_factor * (open_minutes)","base_factor":0.15,"per_minute_factor":0.10}',
     '["紧急关闭","复检箱内温度"]',
     'CARRIER', 4, TRUE, 'CRITICAL'),

    -- NORMAL 剂型预案
    ('RX_NORMAL_OVERHEAT', 'NORMAL', 'OVERHEAT', '常温剂型温度越上限',
     '{"min_c":15.0,"max_c":25.0,"sustained_minutes":30}',
     '{"formula":"dose = base_factor * (peak - max_c) * (duration_min + 1)","base_factor":0.02,"per_minute_factor":0.005}',
     '["通风降温","转移至阴凉处"]',
     'CARRIER', 12, FALSE, 'MAJOR'),

    ('RX_NORMAL_DOOR', 'NORMAL', 'DOOR_OPEN', '常温剂型门开启',
     '{"max_open_duration_sec":300}',
     '{"formula":"dose = base_factor * (open_minutes)","base_factor":0.01,"per_minute_factor":0.005}',
     '["常规培训"]',
     'CARRIER', 48, FALSE, 'MINOR')
ON CONFLICT (code) DO NOTHING;

-- ===== 影响剂量估算规则 =====
INSERT INTO dose_impact_rule (code, dosage_form, exception_type, base_factor, per_minute_factor, per_degree_factor, formula) VALUES
    ('IMP_COLD_OVERHEAT', 'COLD', 'OVERHEAT', 0.05, 0.02, 0.01, 'dose = 0.05 * degrees_over * (1 + 0.02 * minutes)'),
    ('IMP_COLD_UNDERCOOL','COLD', 'UNDERCOOL',0.03, 0.01, 0.01, 'dose = 0.03 * degrees_under * (1 + 0.01 * minutes)'),
    ('IMP_COLD_DOOR',     'COLD', 'DOOR_OPEN',0.04, 0.05, 0.0,  'dose = 0.04 * open_minutes * (1 + 0.05 * per_minute)'),
    ('IMP_COLD_TRACK',    'COLD', 'TRACK_DEVIATION',0.02, 0.0, 0.001,'dose = 0.02 * (deviation_km - threshold)'),
    ('IMP_COLD_DEVICE',   'COLD', 'DEVICE_OFFLINE',0.10, 0.03, 0.0,'dose = 0.10 * (1 + 0.03 * offline_minutes)'),
    ('IMP_COLD_SAMPLING', 'COLD', 'SAMPLING_GAP', 0.02, 0.005, 0.0,'dose = 0.02 * (gap_minutes - threshold)'),
    ('IMP_FROZEN_OVERHEAT','FROZEN','OVERHEAT',0.10, 0.05, 0.05,'dose = 0.10 * degrees_over * (1 + 0.05 * minutes)'),
    ('IMP_FROZEN_DOOR',   'FROZEN','DOOR_OPEN', 0.15, 0.10, 0.0, 'dose = 0.15 * open_minutes * (1 + 0.10 * per_minute)'),
    ('IMP_NORMAL_OVERHEAT','NORMAL','OVERHEAT',0.02, 0.005,0.005,'dose = 0.02 * degrees_over * (1 + 0.005 * minutes)'),
    ('IMP_NORMAL_DOOR',   'NORMAL','DOOR_OPEN', 0.01, 0.005,0.0, 'dose = 0.01 * open_minutes * (1 + 0.005 * per_minute)')
ON CONFLICT (code) DO NOTHING;

-- ===== 责任段判定规则 =====
INSERT INTO responsibility_rule (code, exception_type, rule_expr, default_party, priority) VALUES
    ('RESP_OVERHEAT_SEGMENT', 'OVERHEAT',
     'if (sample_at in [segment.actual_depart_at, segment.actual_arrive_at]) then CARRIER(segment.carrier_id) else ENTERPRISE',
     'CARRIER', 10),
    ('RESP_DOOR_SEGMENT', 'DOOR_OPEN',
     'if (sample_at in [segment.actual_depart_at, segment.actual_arrive_at]) then CARRIER(segment.carrier_id) else ENTERPRISE',
     'CARRIER', 10),
    ('RESP_DEVICE_SEGMENT', 'DEVICE_OFFLINE',
     'if (offline_minutes > 30) then CARRIER else CARRIER', 'CARRIER', 5),
    ('RESP_SAMPLING_SEGMENT', 'SAMPLING_GAP',
     'if (gap_minutes > 30) then CARRIER else CARRIER', 'CARRIER', 20),
    ('RESP_TRACK_SEGMENT', 'TRACK_DEVIATION',
     'if (deviation_km > threshold) then CARRIER else ENTERPRISE', 'CARRIER', 30)
ON CONFLICT (code) DO NOTHING;
