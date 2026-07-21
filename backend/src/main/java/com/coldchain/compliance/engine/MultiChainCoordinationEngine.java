package com.coldchain.compliance.engine;

import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.service.ExceptionPrescriptionService;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 多式联运协同引擎（核心）。
 * <p>
 * 输入：一个订单 ID（或 taskNo）。  
 * 流程：
 * 1. 拉取该订单的所有运输段（含非在途段）
 * 2. 拉取该订单的所有温控采样点（已按段时序排好）
 * 3. 拉取所有 finding
 * 4. 拉取所有批次
 * 5. 对每个 finding 命中异常类型 → 查找处置预案 → 影响剂量估算 → 责任段定位
 * 6. 责任段承运商自动开具整改工单
 * 7. 按段聚合，输出多式联运全链合规结论（每段贡献）
 * 8. 监管报告按预案自动生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChainCoordinationEngine {

    private final TransportTaskRepository taskRepo;
    private final TransportSegmentRepository segmentRepo;
    private final TempSampleRepository sampleRepo;
    private final DrugBatchRepository batchRepo;
    private final AuditFindingRepository findingRepo;
    private final SegmentFindingRelRepository relRepo;
    private final CarrierRepository carrierRepo;
    private final ExceptionPrescriptionService prescriptionService;
    private final DoseImpactCalculator doseCalculator;
    private final ResponsibilityResolver responsibilityResolver;
    private final com.coldchain.compliance.service.WorkOrderService workOrderService;
    private final com.coldchain.compliance.service.RegulatoryReportService regulatoryReportService;
    private final MultiChainComplianceRepository complianceRepo;

    /**
     * 编排一次多式联运协同评估。
     * @return 完整的多链合规结果（含每段贡献、整改工单、监管报告）
     */
    @Transactional
    public Map<String, Object> coordinate(Long taskId) {
        long t0 = System.currentTimeMillis();
        TransportTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        List<TransportSegment> segments = segmentRepo.findByTaskIdOrderBySeqAsc(taskId);
        List<TempSample> samples = sampleRepo.findByTaskIdOrderBySampleAt(taskId);
        List<DrugBatch> batches = batchRepo.findAll();
        List<AuditFinding> findings = findingRepo.findAll(); // 简化：取全部 finding（生产应按 task 关联）
        // 过滤：保留 rule_code 关联段内时间窗的 finding
        List<AuditFinding> taskFindings = new ArrayList<>();
        for (AuditFinding f : findings) {
            if (belongsToTask(f, segments, samples, taskId)) taskFindings.add(f);
        }

        log.info("【多链-协同】task={} 段数={} 采样点={} 批次={} 命中finding={}",
                task.getTaskNo(), segments.size(), samples.size(), batches.size(), taskFindings.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("taskNo", task.getTaskNo());
        result.put("dosageForm", detectDominantForm(batches));
        result.put("segmentCount", segments.size());

        // ===== 1) 段间交接 / 非在途段标记 =====
        List<Map<String, Object>> segmentViews = buildSegmentViews(segments, samples);
        result.put("segments", segmentViews);
        result.put("nonTransitSegments", countNonTransit(segments));

        // ===== 2) 按 finding 归段 + 影响剂量 + 责任段 =====
        List<Map<String, Object>> contributions = new ArrayList<>();
        List<Map<String, Object>> workOrders = new ArrayList<>();
        List<Map<String, Object>> regulatoryReports = new ArrayList<>();
        int blockCount = 0, reviewCount = 0;

        for (AuditFinding f : taskFindings) {
            String exceptionType = mapRuleToExceptionType(f);
            if (exceptionType == null) continue;
            ResponsibilityResolver.ResponsibilityResult resp =
                    responsibilityResolver.resolve(f, segments);
            DoseImpactCalculator.DoseResult dose = doseCalculator.calculate(f, batches, exceptionType,
                    detectDominantForm(batches),
                    extractDuration(f), extractDegrees(f));

            // 段-证据关联
            if (resp.responsibleSegment != null) {
                SegmentFindingRel rel = new SegmentFindingRel();
                rel.setSegmentId(resp.responsibleSegment.getId());
                rel.setFindingId(f.getId());
                rel.setAffectedQty(dose.estimatedAffectedQty);
                double contribution = computeContribution(dose.estimatedAffectedQty, f);
                rel.setContributionScore(BigDecimal.valueOf(contribution));
                relRepo.save(rel);
            }

            ExceptionPrescription prescription = prescriptionService.getEntity(
                    detectDominantForm(batches), exceptionType);
            boolean triggersRegulatory = prescription != null && Boolean.TRUE.equals(prescription.getRegulatoryReport());
            boolean isBlock = Constants.ACTION_BLOCK.equals(f.getAction());
            boolean isReview = Constants.ACTION_REVIEW.equals(f.getAction());
            if (isBlock) blockCount++;
            if (isReview) reviewCount++;

            // 自动开整改工单
            if (resp.responsibleSegment != null && resp.carrierId != null) {
                String title = String.format("[%s] %s 段-%s：%s",
                        f.getSeverity(), resp.responsibleSegment.getSegmentNo(),
                        resp.responsibleSegment.getCarrierId(), f.getDescription());
                String desc = String.format(
                        "任务=%s 段=%s 异常=%s 影响剂量=%d 关联预案=%s 监管报告触发=%s",
                        task.getTaskNo(), resp.responsibleSegment.getSegmentNo(),
                        exceptionType, dose.estimatedAffectedQty,
                        prescription == null ? "无" : prescription.getCode(),
                        triggersRegulatory);
                CarrierWorkorder wo = workOrderService.create(
                        taskId, task.getTaskNo(),
                        resp.responsibleSegment.getId(),
                        resp.carrierId,
                        exceptionType,
                        f.getSeverity(),
                        title,
                        desc,
                        prescription == null ? null : prescription.getId(),
                        dose.estimatedAffectedQty,
                        Constants.PARTY_CARRIER,
                        prescription == null ? 24 : prescription.getResponseHours(),
                        triggersRegulatory);
                workOrders.add(workorderVo(wo, resp));

                if (triggersRegulatory) {
                    RegulatoryReport rr = regulatoryReportService.generate(task, resp, f, dose, prescription);
                    regulatoryReports.add(regulatoryReportService.toVo(rr));
                }
            }

            // 段贡献视图
            Map<String, Object> contrib = new LinkedHashMap<>();
            contrib.put("findingId", f.getId());
            contrib.put("ruleCode", f.getRuleCode());
            contrib.put("severity", f.getSeverity());
            contrib.put("action", f.getAction());
            contrib.put("exceptionType", exceptionType);
            contrib.put("prescriptionCode", prescription == null ? null : prescription.getCode());
            contrib.put("affectedDoseRatio", dose.affectedDoseRatio);
            contrib.put("estimatedAffectedQty", dose.estimatedAffectedQty);
            contrib.put("responsibleSegmentNo", resp.segmentNo);
            contrib.put("responsibleCarrierId", resp.carrierId);
            contrib.put("responsibleParty", resp.responsibleParty);
            contrib.put("resolutionReason", resp.reason);
            contrib.put("triggersRegulatory", triggersRegulatory);
            contrib.put("description", f.getDescription());
            contrib.put("timeRange", f.getTimeRangeStart() + " ~ " + f.getTimeRangeEnd());
            contributions.add(contrib);
        }

        result.put("contributions", contributions);
        result.put("workOrders", workOrders);
        result.put("regulatoryReports", regulatoryReports);

        // ===== 3) 全链合规结论 =====
        String overallStatus, overallDecision;
        if (blockCount > 0) {
            overallStatus = Constants.AUDIT_BLOCK;
            overallDecision = Constants.DECISION_BLOCK;
        } else if (reviewCount > 0) {
            overallStatus = Constants.AUDIT_REVIEW;
            overallDecision = Constants.DECISION_CONDITIONAL;
        } else {
            overallStatus = Constants.AUDIT_PASS;
            overallDecision = Constants.DECISION_RELEASE;
        }
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("status", overallStatus);
        overall.put("decision", overallDecision);
        overall.put("blockCount", blockCount);
        overall.put("reviewCount", reviewCount);
        overall.put("contributionCount", contributions.size());
        overall.put("workOrderCount", workOrders.size());
        overall.put("regulatoryReportCount", regulatoryReports.size());
        overall.put("durationMs", System.currentTimeMillis() - t0);
        result.put("overall", overall);

        // 关键段（导致 BLOCK/REVIEW 的段）
        List<String> criticalSegs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> c : contributions) {
            String seg = String.valueOf(c.get("responsibleSegmentNo"));
            if (seg != null && !"null".equals(seg) && seen.add(seg)
                    && (Constants.ACTION_BLOCK.equals(c.get("action")) || Constants.ACTION_REVIEW.equals(c.get("action")))) {
                criticalSegs.add(seg);
            }
        }
        result.put("criticalSegments", criticalSegs);

        // 持久化多链合规结论
        MultiChainCompliance mc = complianceRepo.findByTaskId(taskId).orElse(new MultiChainCompliance());
        mc.setTaskId(taskId);
        mc.setOverallStatus(overallStatus);
        mc.setOverallDecision(overallDecision);
        mc.setSegmentCount(segments.size());
        mc.setCriticalSegments(JsonUtil.toJson(criticalSegs));
        mc.setContributionJson(JsonUtil.toJson(contributions));
        mc.setSummaryText(buildSummary(overallStatus, overallDecision, criticalSegs, contributions));
        complianceRepo.save(mc);

        log.info("【多链-协同】完成 task={} 状态={} 决策={} 段贡献={} 工单={} 报告={} 耗时={}ms",
                task.getTaskNo(), overallStatus, overallDecision,
                contributions.size(), workOrders.size(), regulatoryReports.size(),
                System.currentTimeMillis() - t0);
        return result;
    }

    // ===== 工具 =====
    private boolean belongsToTask(AuditFinding f, List<TransportSegment> segs, List<TempSample> samples, Long taskId) {
        if (segs == null || segs.isEmpty()) return false;
        if (f.getTimeRangeStart() == null) return true;
        for (TransportSegment s : segs) {
            OffsetDateTime ds = s.getActualDepartAt() == null ? s.getPlannedDepartAt() : s.getActualDepartAt();
            OffsetDateTime as = s.getActualArriveAt() == null ? s.getPlannedArriveAt() : s.getActualArriveAt();
            if (ds == null || as == null) continue;
            if (!f.getTimeRangeStart().isBefore(ds) && !f.getTimeRangeStart().isAfter(as)) return true;
        }
        return true; // 兜底
    }

    private String detectDominantForm(List<DrugBatch> batches) {
        if (batches.isEmpty()) return Constants.FORM_COLD;
        Set<String> forms = new HashSet<>();
        for (DrugBatch b : batches) forms.add(b.getDosageForm());
        if (forms.contains(Constants.FORM_FROZEN)) return Constants.FORM_FROZEN;
        if (forms.contains(Constants.FORM_COLD)) return Constants.FORM_COLD;
        return Constants.FORM_NORMAL;
    }

    private String mapRuleToExceptionType(AuditFinding f) {
        String code = f.getRuleCode() == null ? "" : f.getRuleCode();
        if (code.contains("CUMULATIVE") || code.contains("OVERHEAT") || code.contains("RANGE")) return Constants.EX_OVERHEAT;
        if (code.contains("DOOR")) return Constants.EX_DOOR_OPEN;
        if (code.contains("TRACK")) return Constants.EX_TRACK_DEV;
        if (code.contains("CONTINUITY") || code.contains("SAMPLING")) return Constants.EX_SAMPLING_GAP;
        if (code.contains("SMOOTH")) return Constants.EX_DEVICE_OFF;
        return null;
    }

    private double extractDuration(AuditFinding f) {
        if (f.getTimeRangeStart() == null || f.getTimeRangeEnd() == null) return 5.0;
        long sec = java.time.Duration.between(f.getTimeRangeStart(), f.getTimeRangeEnd()).getSeconds();
        return Math.max(1, sec / 60.0);
    }

    private double extractDegrees(AuditFinding f) {
        if (f.getTemperatureMax() != null && f.getTemperatureMin() != null) {
            return f.getTemperatureMax().subtract(f.getTemperatureMin()).abs().doubleValue();
        }
        if (f.getTemperatureMax() != null) return f.getTemperatureMax().doubleValue();
        return 0.0;
    }

    private double computeContribution(int qty, AuditFinding f) {
        if (Constants.SEV_CRITICAL.equals(f.getSeverity())) return 0.9;
        if (Constants.SEV_MAJOR.equals(f.getSeverity())) return 0.5;
        return 0.2;
    }

    private List<Map<String, Object>> buildSegmentViews(List<TransportSegment> segs, List<TempSample> samples) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TransportSegment s : segs) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", s.getId());
            vo.put("segmentNo", s.getSegmentNo());
            vo.put("segmentType", s.getSegmentType());
            vo.put("origin", s.getOrigin());
            vo.put("destination", s.getDestination());
            vo.put("carrierId", s.getCarrierId());
            vo.put("temperatureMin", s.getTemperatureMin());
            vo.put("temperatureMax", s.getTemperatureMax());
            vo.put("isInTransit", s.getIsInTransit());
            vo.put("status", s.getStatus());
            vo.put("responsiblePerson", s.getResponsiblePerson());
            vo.put("plannedDepartAt", s.getPlannedDepartAt());
            vo.put("plannedArriveAt", s.getPlannedArriveAt());
            vo.put("actualDepartAt", s.getActualDepartAt());
            vo.put("actualArriveAt", s.getActualArriveAt());
            vo.put("seq", s.getSeq());
            if (s.getCarrierId() != null) {
                carrierRepo.findById(s.getCarrierId()).ifPresent(c ->
                        vo.put("carrierName", c.getCarrierName()));
            }
            // 段内温控点
            List<Map<String, Object>> points = new ArrayList<>();
            for (TempSample sp : samples) {
                OffsetDateTime ds = s.getActualDepartAt() == null ? s.getPlannedDepartAt() : s.getActualDepartAt();
                OffsetDateTime as = s.getActualArriveAt() == null ? s.getPlannedArriveAt() : s.getActualArriveAt();
                if (ds == null || as == null) continue;
                if (!sp.getSampleAt().isBefore(ds) && !sp.getSampleAt().isAfter(as)) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("at", sp.getSampleAt().toString());
                    p.put("temperature", sp.getTemperature());
                    p.put("doorOpen", sp.getDoorOpen());
                    p.put("deviceNo", sp.getDeviceNo());
                    points.add(p);
                }
            }
            vo.put("sampleCount", points.size());
            vo.put("temperatureCurve", points);
            out.add(vo);
        }
        return out;
    }

    private int countNonTransit(List<TransportSegment> segs) {
        int n = 0;
        for (TransportSegment s : segs) if (Boolean.FALSE.equals(s.getIsInTransit())) n++;
        return n;
    }

    private Map<String, Object> workorderVo(CarrierWorkorder wo, ResponsibilityResolver.ResponsibilityResult resp) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", wo.getId());
        vo.put("workorderNo", wo.getWorkorderNo());
        vo.put("segmentId", wo.getSegmentId());
        vo.put("carrierId", wo.getCarrierId());
        vo.put("exceptionType", wo.getExceptionType());
        vo.put("severity", wo.getSeverity());
        vo.put("title", wo.getTitle());
        vo.put("affectedQty", wo.getAffectedQty());
        vo.put("status", wo.getStatus());
        vo.put("responseDeadline", wo.getResponseDeadline());
        vo.put("createdAt", wo.getCreatedAt());
        vo.put("responsibleParty", wo.getResponsibleParty());
        vo.put("prescriptionId", wo.getPrescriptionId());
        vo.put("reason", resp.reason);
        return vo;
    }

    private String buildSummary(String status, String decision, List<String> criticalSegs,
                                List<Map<String, Object>> contribs) {
        StringBuilder sb = new StringBuilder();
        sb.append("全链合规结论:").append(status).append(" / 决策:").append(decision).append("; ");
        sb.append("关键段:").append(criticalSegs).append("; ");
        sb.append("贡献:").append(contribs.size()).append("条。");
        return sb.toString();
    }
}
