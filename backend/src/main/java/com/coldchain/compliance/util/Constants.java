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
}
