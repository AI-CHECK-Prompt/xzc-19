package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 段间交接记录。前后两段温控/轨迹/设备数据无缝衔接，
 * is_non_transit=true 时为"非在途段"（如堆场停放），不计入合规审计。
 */
@Data
@Entity
@Table(name = "segment_handover")
public class SegmentHandover {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "from_segment_id", nullable = false)
    private Long fromSegmentId;
    @Column(name = "to_segment_id", nullable = false)
    private Long toSegmentId;
    @Column(name = "handover_at", nullable = false)
    private OffsetDateTime handoverAt;
    @Column(name = "from_carrier_id")
    private Long fromCarrierId;
    @Column(name = "to_carrier_id")
    private Long toCarrierId;
    @Column(name = "last_temp_c")
    private BigDecimal lastTempC;
    @Column(name = "last_humidity")
    private BigDecimal lastHumidity;
    @Column(name = "last_device_no")
    private String lastDeviceNo;
    @Column(name = "continuity_ok", nullable = false)
    private Boolean continuityOk = true;
    @Column(name = "device_handoff_ok", nullable = false)
    private Boolean deviceHandoffOk = true;
    @Column(name = "gap_minutes")
    private Integer gapMinutes;
    @Column(name = "is_non_transit", nullable = false)
    private Boolean isNonTransit = false;
    @Column(name = "storage_location")
    private String storageLocation;
    @Column(name = "storage_temperature")
    private BigDecimal storageTemperature;
    @Column(name = "evidence_json")
    private String evidenceJson;
    private String operator;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
