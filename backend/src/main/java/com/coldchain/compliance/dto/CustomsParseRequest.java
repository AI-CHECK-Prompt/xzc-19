package com.coldchain.compliance.dto;

import lombok.Data;
import java.util.List;

@Data
public class CustomsParseRequest {
    private String declNo;
    private Long taskId;
    private String language = "zh-CN";
    private String rawText;
    /** 多语言支持的字段抽取结果（已结构化） */
    private List<DeclaredBatch> declaredBatches;
    private String consignor;
    private String consignee;
    private String totalValue;
    private String currency;
    private String declDate;

    @Data
    public static class DeclaredBatch {
        private String batchNo;
        private Integer qty;
        private String product;
    }
}
