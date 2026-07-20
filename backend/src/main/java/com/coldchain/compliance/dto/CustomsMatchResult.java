package com.coldchain.compliance.dto;

import lombok.Data;
import java.util.List;

@Data
public class CustomsMatchResult {
    private Long declarationId;
    private String declNo;
    private Long taskId;
    private int total;
    private int matched;
    private int mismatched;
    private int missing;
    private boolean overallOk;
    private List<BatchItem> items;

    @Data
    public static class BatchItem {
        private String declaredBatchNo;
        private String matchedBatchNo;
        private String declaredProduct;
        private Integer declaredQty;
        private Integer actualQty;
        private String matchStatus;   // MATCHED / MISMATCH / MISSING
        private String detail;
    }
}
