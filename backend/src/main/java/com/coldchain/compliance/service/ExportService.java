package com.coldchain.compliance.service;

import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 飞检数据导出服务：XML / PDF / Excel 三格式。
 * <p>
 * 导出后立即计算文件 SHA-256 哈希 + RSA 签名，写入 export_record。
 * 飞检期间可凭哈希与签名验证导出文件真实性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final TransportTaskRepository taskRepo;
    private final TempSampleRepository sampleRepo;
    private final TrackPointRepository trackRepo;
    private final AuditReportRepository auditRepo;
    private final AuditFindingRepository findingRepo;
    private final ReleaseDecisionRepository decisionRepo;
    private final CustomsDeclarationRepository customsRepo;
    private final OperationLogRepository opLogRepo;
    private final ExportRecordRepository exportRepo;
    private final SignatureUtil signatureUtil;

    @Value("${coldchain.export.temp-dir:/data/exports}")
    private String tempDir;

    public String exportXml(String taskNo, String requestedBy) throws Exception {
        TransportTask task = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        Path out = ensureFile(taskNo, "xml");
        XmlReport r = buildXmlReport(task);
        JAXBContext ctx = JAXBContext.newInstance(XmlReport.class);
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        try (OutputStream os = new FileOutputStream(out.toFile())) {
            m.marshal(r, os);
        }
        return finalizeExport(out, task, "XML", requestedBy);
    }

    public String exportPdf(String taskNo, String requestedBy) throws Exception {
        TransportTask task = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        Path out = ensureFile(taskNo, "pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(50, 770);
                cs.showText("Cold Chain Compliance Report");
                cs.endText();

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 11);
                cs.newLineAtOffset(50, 740);
                int line = 0;
                List<String> lines = buildReportLines(task);
                for (String ln : lines) {
                    cs.showText(ln);
                    cs.newLineAtOffset(0, -16);
                    line++;
                    if (line > 42) break; // 简化：单页
                }
                cs.endText();
            }
            doc.save(out.toFile());
        }
        return finalizeExport(out, task, "PDF", requestedBy);
    }

    public String exportExcel(String taskNo, String requestedBy) throws Exception {
        TransportTask task = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        Path out = ensureFile(taskNo, "xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            // 概览 sheet
            Sheet overview = wb.createSheet("概览");
            Row r0 = overview.createRow(0);
            r0.createCell(0).setCellValue("任务号");
            r0.createCell(1).setCellValue(task.getTaskNo());
            r0.createCell(2).setCellValue("起运地");
            r0.createCell(3).setCellValue(task.getOrigin());
            r0.createCell(4).setCellValue("目的地");
            r0.createCell(5).setCellValue(task.getDestination());
            r0.createCell(6).setCellValue("状态");
            r0.createCell(7).setCellValue(task.getStatus());

            // 温度曲线 sheet
            Sheet tempSheet = wb.createSheet("温度曲线");
            tempSheet.createRow(0).createCell(0).setCellValue("设备号");
            tempSheet.getRow(0).createCell(1).setCellValue("采样时间");
            tempSheet.getRow(0).createCell(2).setCellValue("温度(℃)");
            tempSheet.getRow(0).createCell(3).setCellValue("湿度(%)");
            tempSheet.getRow(0).createCell(4).setCellValue("门状态");
            tempSheet.getRow(0).createCell(5).setCellValue("纬度");
            tempSheet.getRow(0).createCell(6).setCellValue("经度");
            tempSheet.getRow(0).createCell(7).setCellValue("哈希");
            int row = 1;
            for (TempSample s : sampleRepo.findByTaskIdOrderBySampleAt(task.getId())) {
                Row rr = tempSheet.createRow(row++);
                rr.createCell(0).setCellValue(s.getDeviceNo());
                rr.createCell(1).setCellValue(s.getSampleAt().toString());
                rr.createCell(2).setCellValue(s.getTemperature().doubleValue());
                rr.createCell(3).setCellValue(s.getHumidity() == null ? 0 : s.getHumidity().doubleValue());
                rr.createCell(4).setCellValue(Boolean.TRUE.equals(s.getDoorOpen()) ? "OPEN" : "CLOSED");
                rr.createCell(5).setCellValue(s.getLatitude() == null ? 0 : s.getLatitude().doubleValue());
                rr.createCell(6).setCellValue(s.getLongitude() == null ? 0 : s.getLongitude().doubleValue());
                rr.createCell(7).setCellValue(s.getPayloadHash());
            }

            // 审计 sheet
            Sheet auditSheet = wb.createSheet("审计");
            auditSheet.createRow(0).createCell(0).setCellValue("规则");
            auditSheet.getRow(0).createCell(1).setCellValue("严重程度");
            auditSheet.getRow(0).createCell(2).setCellValue("动作");
            auditSheet.getRow(0).createCell(3).setCellValue("时间区间");
            auditSheet.getRow(0).createCell(4).setCellValue("说明");
            int ar = 1;
            for (AuditReport a : auditRepo.findByTaskIdOrderByStartedAtDesc(task.getId())) {
                for (AuditFinding f : findingRepo.findByAuditId(a.getId())) {
                    Row rr = auditSheet.createRow(ar++);
                    rr.createCell(0).setCellValue(f.getRuleCode());
                    rr.createCell(1).setCellValue(f.getSeverity());
                    rr.createCell(2).setCellValue(f.getAction());
                    rr.createCell(3).setCellValue(
                            (f.getTimeRangeStart() == null ? "" : f.getTimeRangeStart().toString())
                            + " ~ " + (f.getTimeRangeEnd() == null ? "" : f.getTimeRangeEnd().toString()));
                    rr.createCell(4).setCellValue(f.getDescription());
                }
            }

            try (OutputStream os = new FileOutputStream(out.toFile())) {
                wb.write(os);
            }
        }
        return finalizeExport(out, task, "EXCEL", requestedBy);
    }

    private String finalizeExport(Path out, TransportTask task, String fmt, String requestedBy) throws IOException {
        byte[] data = Files.readAllBytes(out);
        String hash = signatureUtil.sha256Hex(data);
        String sig = signatureUtil.sign(hash);
        ExportRecord er = new ExportRecord();
        er.setTaskId(task.getId());
        er.setFormat(fmt);
        er.setFilePath(out.toString());
        er.setFileHash(hash);
        er.setSignature(sig);
        er.setRequestedBy(requestedBy);
        exportRepo.save(er);
        log.info("【导出-飞检】task={} fmt={} file={} hash={}", task.getTaskNo(), fmt, out, hash);
        return out.toString();
    }

    private Path ensureFile(String taskNo, String ext) throws IOException {
        Files.createDirectories(Paths.get(tempDir));
        String name = String.format("FLIGHT_INSPECTION_%s_%s.%s",
                taskNo,
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now()),
                ext);
        return Paths.get(tempDir, name);
    }

    private List<String> buildReportLines(TransportTask task) {
        List<String> lines = new ArrayList<>();
        lines.add("Task No: " + task.getTaskNo());
        lines.add("Origin: " + task.getOrigin() + " -> " + task.getDestination());
        lines.add("Departure: " + task.getDepartureAt() + "  Arrival: " + task.getArrivalAt());
        lines.add("Status: " + task.getStatus());
        lines.add("--- Audit Findings ---");
        for (AuditReport a : auditRepo.findByTaskIdOrderByStartedAtDesc(task.getId())) {
            lines.add("Audit " + a.getId() + " status=" + a.getStatus()
                    + " findings=" + a.getFindingCount());
            for (AuditFinding f : findingRepo.findByAuditId(a.getId())) {
                lines.add("  - " + f.getRuleCode() + " [" + f.getSeverity() + "/" + f.getAction() + "] "
                        + f.getDescription());
            }
        }
        lines.add("--- Decisions ---");
        for (ReleaseDecision d : decisionRepo.findByTaskIdOrderByDecidedAtDesc(task.getId())) {
            lines.add("Decision " + d.getId() + " by " + d.getDecidedBy() + " : " + d.getDecision());
        }
        return lines;
    }

    private XmlReport buildXmlReport(TransportTask task) {
        XmlReport r = new XmlReport();
        r.taskNo = task.getTaskNo();
        r.origin = task.getOrigin();
        r.destination = task.getDestination();
        r.status = task.getStatus();
        r.departureAt = task.getDepartureAt() == null ? null : task.getDepartureAt().toString();
        r.arrivalAt = task.getArrivalAt() == null ? null : task.getArrivalAt().toString();
        for (TempSample s : sampleRepo.findByTaskIdOrderBySampleAt(task.getId())) {
            XmlSample xs = new XmlSample();
            xs.deviceNo = s.getDeviceNo();
            xs.sampleAt = s.getSampleAt().toString();
            xs.temperature = s.getTemperature().toPlainString();
            xs.humidity = s.getHumidity() == null ? null : s.getHumidity().toPlainString();
            xs.doorOpen = s.getDoorOpen();
            xs.lat = s.getLatitude() == null ? null : s.getLatitude().toPlainString();
            xs.lng = s.getLongitude() == null ? null : s.getLongitude().toPlainString();
            xs.hash = s.getPayloadHash();
            r.samples.add(xs);
        }
        for (AuditReport a : auditRepo.findByTaskIdOrderByStartedAtDesc(task.getId())) {
            XmlAudit xa = new XmlAudit();
            xa.auditId = a.getId();
            xa.status = a.getStatus();
            xa.payloadHash = a.getPayloadHash();
            xa.signature = a.getSignature();
            for (AuditFinding f : findingRepo.findByAuditId(a.getId())) {
                XmlFinding xf = new XmlFinding();
                xf.ruleCode = f.getRuleCode();
                xf.severity = f.getSeverity();
                xf.action = f.getAction();
                xf.description = f.getDescription();
                xa.findings.add(xf);
            }
            r.audits.add(xa);
        }
        for (ReleaseDecision d : decisionRepo.findByTaskIdOrderByDecidedAtDesc(task.getId())) {
            XmlDecision xd = new XmlDecision();
            xd.decision = d.getDecision();
            xd.decidedBy = d.getDecidedBy();
            xd.decidedAt = d.getDecidedAt() == null ? null : d.getDecidedAt().toString();
            xd.signature = d.getSignature();
            r.decisions.add(xd);
        }
        return r;
    }

    // ============= XML 模型 =============
    @XmlRootElement(name = "ColdChainReport")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlReport {
        public String taskNo;
        public String origin;
        public String destination;
        public String status;
        public String departureAt;
        public String arrivalAt;
        @XmlElement(name = "Sample")
        public List<XmlSample> samples = new ArrayList<>();
        @XmlElement(name = "Audit")
        public List<XmlAudit> audits = new ArrayList<>();
        @XmlElement(name = "Decision")
        public List<XmlDecision> decisions = new ArrayList<>();
    }
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlSample {
        public String deviceNo;
        public String sampleAt;
        public String temperature;
        public String humidity;
        public Boolean doorOpen;
        public String lat;
        public String lng;
        public String hash;
    }
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlAudit {
        public Long auditId;
        public String status;
        public String payloadHash;
        public String signature;
        @XmlElement(name = "Finding")
        public List<XmlFinding> findings = new ArrayList<>();
    }
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlFinding {
        public String ruleCode;
        public String severity;
        public String action;
        public String description;
    }
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlDecision {
        public String decision;
        public String decidedBy;
        public String decidedAt;
        public String signature;
    }
}
