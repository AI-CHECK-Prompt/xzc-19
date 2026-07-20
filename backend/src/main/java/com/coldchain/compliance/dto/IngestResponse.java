package com.coldchain.compliance.dto;

import lombok.Data;

@Data
public class IngestResponse {
    private int sampleAccepted;
    private int sampleRejected;
    private int trackAccepted;
    private int trackRejected;
    private String message;
    private Long elapsedMs;
}
