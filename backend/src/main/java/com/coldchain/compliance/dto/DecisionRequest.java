package com.coldchain.compliance.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DecisionRequest {
    private String taskNo;
    private String decision;          // RELEASE / BLOCK / CONDITIONAL_RELEASE
    private String decidedBy;
    private String comment;
    private Map<String, Object> extra;
    private List<String> acknowledgeFindings;  // 人工确认的 findingId（CONDITIONAL 时使用）
}
