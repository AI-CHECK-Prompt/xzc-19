package com.coldchain.compliance.util;

/**
 * 业务常量集中地。
 */
public final class Constants {
    private Constants() {}

    // 剂型
    public static final String FORM_COLD = "COLD";        // 2-8℃冷藏
    public static final String FORM_FROZEN = "FROZEN";    // -20℃以下冷冻
    public static final String FORM_NORMAL = "NORMAL";    // 15-25℃常温

    // 任务状态
    public static final String TASK_CREATED = "CREATED";
    public static final String TASK_IN_TRANSIT = "IN_TRANSIT";
    public static final String TASK_ARRIVED = "ARRIVED";
    public static final String TASK_AUDITING = "AUDITING";
    public static final String TASK_RELEASED = "RELEASED";
    public static final String TASK_BLOCKED = "BLOCKED";
    public static final String TASK_CONDITIONAL = "CONDITIONAL";

    // 审计
    public static final String AUDIT_PASS = "PASS";
    public static final String AUDIT_BLOCK = "BLOCK";
    public static final String AUDIT_REVIEW = "REVIEW";
    public static final String AUDIT_RUNNING = "RUNNING";

    // 规则分类
    public static final String CAT_CONTINUITY = "CONTINUITY";
    public static final String CAT_CUMULATIVE = "CUMULATIVE";
    public static final String CAT_RANGE = "RANGE";
    public static final String CAT_DOOR = "DOOR";
    public static final String CAT_TRACK = "TRACK";
    public static final String CAT_SMOOTH = "SMOOTH";

    // 动作
    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_REVIEW = "REVIEW";

    // 严重程度
    public static final String SEV_CRITICAL = "CRITICAL";
    public static final String SEV_MAJOR = "MAJOR";
    public static final String SEV_MINOR = "MINOR";

    // 决策
    public static final String DECISION_RELEASE = "RELEASE";
    public static final String DECISION_BLOCK = "BLOCK";
    public static final String DECISION_CONDITIONAL = "CONDITIONAL_RELEASE";

    // 角色
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_AUDITOR = "AUDITOR";
    public static final String ROLE_QA = "QA";
    public static final String ROLE_CUSTOMS = "CUSTOMS";
    public static final String ROLE_ADMIN = "ADMIN";

    // 段类型
    public static final String SEG_ROAD = "ROAD";
    public static final String SEG_SEA = "SEA";
    public static final String SEG_AIR = "AIR";
    public static final String SEG_RAIL = "RAIL";
    public static final String SEG_STORAGE = "STORAGE";   // 堆场/中转（非在途）

    // 段状态
    public static final String SEG_STATUS_PLANNED = "PLANNED";
    public static final String SEG_STATUS_DEPARTED = "DEPARTED";
    public static final String SEG_STATUS_ARRIVED = "ARRIVED";
    public static final String SEG_STATUS_HANDED_OVER = "HANDED_OVER";

    // 异常类型
    public static final String EX_OVERHEAT = "OVERHEAT";
    public static final String EX_UNDERCOOL = "UNDERCOOL";
    public static final String EX_DOOR_OPEN = "DOOR_OPEN";
    public static final String EX_TRACK_DEV = "TRACK_DEVIATION";
    public static final String EX_DEVICE_OFF = "DEVICE_OFFLINE";
    public static final String EX_SAMPLING_GAP = "SAMPLING_GAP";

    // 责任方
    public static final String PARTY_CARRIER = "CARRIER";
    public static final String PARTY_ENTERPRISE = "ENTERPRISE";
    public static final String PARTY_SUPPLIER = "SUPPLIER";

    // 工单状态
    public static final String WO_OPEN = "OPEN";
    public static final String WO_IN_PROGRESS = "IN_PROGRESS";
    public static final String WO_RESOLVED = "RESOLVED";
    public static final String WO_CLOSED = "CLOSED";
    public static final String WO_OVERDUE = "OVERDUE";

    // 监管报告
    public static final String RR_DRAFT = "DRAFT";
    public static final String RR_SUBMITTED = "SUBMITTED";
    public static final String RR_ACK = "ACKNOWLEDGED";
}
