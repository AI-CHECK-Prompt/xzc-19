package com.coldchain.compliance.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class TrackPointDto {
    private String deviceNo;
    private String taskNo;
    private Long seqNo;
    private OffsetDateTime sampleAt;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal speedKmh;
    private BigDecimal heading;
    private String signature;
}
