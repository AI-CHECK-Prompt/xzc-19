package com.coldchain.compliance.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 温控采样点 DTO（设备上报载荷）。
 */
@Data
public class TempSampleDto {
    private String deviceNo;
    private String taskNo;          // 设备侧可携带任务号
    private Long seqNo;             // 设备内递增序号
    private OffsetDateTime sampleAt;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private Boolean doorOpen;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String driverEvent;
    private String signature;       // 设备侧签名（可为空，系统会重新签一次）
}
