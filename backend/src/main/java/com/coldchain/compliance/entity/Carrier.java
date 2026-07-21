package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 多承运商档案。一笔多式联运订单的每段运输可关联一个承运商。
 */
@Data
@Entity
@Table(name = "carrier")
public class Carrier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "carrier_code", nullable = false, unique = true)
    private String carrierCode;
    @Column(name = "carrier_name", nullable = false)
    private String carrierName;
    @Column(name = "carrier_type", nullable = false)
    private String carrierType; // ROAD / SEA / AIR / RAIL / STORAGE
    @Column(name = "license_no")
    private String licenseNo;
    private String country;
    @Column(name = "contact_name")
    private String contactName;
    @Column(name = "contact_phone")
    private String contactPhone;
    @Column(name = "sla_score")
    private BigDecimal slaScore;
    private Boolean enabled = true;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
