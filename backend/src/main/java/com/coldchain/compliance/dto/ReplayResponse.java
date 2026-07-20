package com.coldchain.compliance.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ReplayResponse {
    private String taskNo;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private List<Map<String, Object>> timeline;       // 时间轴
    private List<Map<String, Object>> temperatureCurve;
    private List<Map<String, Object>> trackPoints;
    private Map<String, Object> auditSummary;
    private List<Map<String, Object>> decisions;
    private List<Map<String, Object>> operationLogs;
    private Map<String, Object> integrityCheck;        // 哈希链校验结果
}
