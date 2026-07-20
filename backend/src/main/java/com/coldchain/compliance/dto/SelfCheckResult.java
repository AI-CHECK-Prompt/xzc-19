package com.coldchain.compliance.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SelfCheckResult {
    private boolean overallOk;
    private long timestamp;
    private List<CheckItem> items;
    private Map<String, Object> details;

    @Data
    public static class CheckItem {
        private String name;
        private String category;       // CORE / DATA / AUDIT / EXPORT / IMMUTABLE
        private boolean passed;
        private String message;
        private Long durationMs;
        private Map<String, Object> extra;
    }
}
