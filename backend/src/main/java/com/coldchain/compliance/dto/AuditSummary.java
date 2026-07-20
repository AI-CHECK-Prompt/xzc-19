package com.coldchain.compliance.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AuditSummary {
    private Long auditId;
    private Long taskId;
    private String status;            // PASS / BLOCK / REVIEW
    private int totalFindings;
    private int criticalCount;
    private int majorCount;
    private int minorCount;
    private int blockCount;
    private int reviewCount;
    private long durationMs;
    private List<FindingVo> findings;
    private Map<String, Object> rulesExecuted;
    private String payloadHash;
    private String signature;

    @Data
    public static class FindingVo {
        private Long id;
        private String ruleCode;
        private String ruleName;
        private String severity;
        private String action;
        private String timeRangeStart;
        private String timeRangeEnd;
        private Integer affectedQty;
        private String temperatureMin;
        private String temperatureMax;
        private String description;
        private String evidence;
    }
}
