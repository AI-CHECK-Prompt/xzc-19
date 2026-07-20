package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "export_record")
public class ExportRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id")
    private Long taskId;
    @Column(nullable = false, length = 10)
    private String format;  // XML / PDF / EXCEL
    @Column(name = "file_path", nullable = false)
    private String filePath;
    @Column(name = "file_hash", nullable = false, length = 128)
    private String fileHash;
    @Column(nullable = false, columnDefinition = "text")
    private String signature;
    @Column(name = "requested_by", nullable = false)
    private String requestedBy;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
