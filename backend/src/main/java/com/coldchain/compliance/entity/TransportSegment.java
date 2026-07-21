package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 运输段。一笔订单可拆为多段（多式联运）。
 * is_in_transit=false 表示"非在途段"（如港口堆场停放），不计入合规审计。
 */
@Data
@Entity
@Table(name = "transport_segment")
public class TransportSegment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "segment_no", nullable = false)
    private String segmentNo;          // S1 / S2 / S3...
    @Column(name = "segment_type", nullable = false)
    private String segmentType;        // ROAD / SEA / AIR / RAIL / STORAGE
    @Column(nullable = false)
    private String origin;
    @Column(nullable = false)
    private String destination;
    @Column(name = "carrier_id")
    private Long carrierId;
    @Column(name = "transport_tool")
    private String transportTool;      // 冷藏车 / 冷藏箱 / 货机
    @Column(name = "tool_no")
    private String toolNo;
    @Column(name = "temperature_min")
    private BigDecimal temperatureMin;
    @Column(name = "temperature_max")
    private BigDecimal temperatureMax;
    @Column(name = "planned_depart_at")
    private OffsetDateTime plannedDepartAt;
    @Column(name = "planned_arrive_at")
    private OffsetDateTime plannedArriveAt;
    @Column(name = "actual_depart_at")
    private OffsetDateTime actualDepartAt;
    @Column(name = "actual_arrive_at")
    private OffsetDateTime actualArriveAt;
    @Column(name = "is_in_transit", nullable = false)
    private Boolean isInTransit = true;
    @Column(nullable = false)
    private String status = "PLANNED"; // PLANNED / DEPARTED / ARRIVED / HANDED_OVER
    @Column(name = "responsible_person")
    private String responsiblePerson;
    @Column(name = "responsible_phone")
    private String responsiblePhone;
    private String remark;
    @Column(nullable = false)
    private Integer seq;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
