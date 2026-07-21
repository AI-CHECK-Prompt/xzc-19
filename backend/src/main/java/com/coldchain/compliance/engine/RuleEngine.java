package com.coldchain.compliance.engine;

import com.coldchain.compliance.dto.AuditSummary;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GxP 合规规则引擎：单笔运输任务的全量自动审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {

    private final ComplianceRuleRepository ruleRepo;
    private final TempSampleRepository sampleRepo;
    private final TrackPointRepository trackRepo;
    private final DrugBatchRepository batchRepo;
    private final AuditReportRepository auditRepo;
    private final AuditFindingRepository findingRepo;
    private final TransportTaskRepository taskRepo;
    private final SignatureUtil signatureUtil;

    @Transactional
    public AuditSummary audit(Long taskId) {
        long t0 = System.currentTimeMillis();
        TransportTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        // 简化：实际应通过 task_batch_rel 查询相关批次的剂型
        List<DrugBatch> batches = batchRepo.findAll();
        List<TempSample> samples = sampleRepo.findByTaskIdOrderBySampleAt(taskId);
        List<TrackPoint> tracks = trackRepo.findByTaskIdOrderBySampleAt(taskId);

        log.info("【规则-审计】开始 task={} samples={} tracks={}", task.getTaskNo(),
                samples.size(), tracks.size());

        String dominantForm = detectDominantForm(batches);
        List<ComplianceRule> rules = ruleRepo.findByEnabledTrue();
        List<ComplianceRule> applicable = new ArrayList<>();
        for (ComplianceRule r : rules) {
            if (r.getDosageForm() == null || r.getDosageForm().equals(dominantForm)) {
                applicable.add(r);
            }
        }

        AuditReport report = new AuditReport();
        report.setTaskId(taskId);
        report.setStartedAt(ClockUtil.nowUtc());
        report.setStatus(Constants.AUDIT_RUNNING);
        report = auditRepo.save(report);

        List<AuditSummary.FindingVo> findings = new ArrayList<>();
        int critical = 0, major = 0, minor = 0, block = 0, review = 0;
        Map<String, Object> rulesExecuted = new LinkedHashMap<>();

        for (ComplianceRule rule : applicable) {
            List<AuditFinding> ruleFindings = new ArrayList<>();
            try {
                if (Constants.CAT_CONTINUITY.equals(rule.getCategory())) {
                    ruleFindings = checkContinuity(task, samples, rule);
                } else if (Constants.CAT_CUMULATIVE.equals(rule.getCategory())) {
                    ruleFindings = checkCumulative(task, samples, rule);
                } else if (Constants.CAT_RANGE.equals(rule.getCategory())) {
                    ruleFindings = checkRange(task, samples, rule, dominantForm);
                } else if (Constants.CAT_DOOR.equals(rule.getCategory())) {
                    ruleFindings = checkDoor(task, samples, rule);
                } else if (Constants.CAT_TRACK.equals(rule.getCategory())) {
                    ruleFindings = checkTrack(task, tracks, rule);
                } else if (Constants.CAT_SMOOTH.equals(rule.getCategory())) {
                    ruleFindings = checkSmoothness(task, samples, rule);
                } else {
                    log.warn("【规则-审计】未知规则类别: {}", rule.getCategory());
                }
            } catch (Exception e) {
                log.error("【规则-审计】规则执行失败: rule={} err={}", rule.getCode(), e.getMessage());
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("category", rule.getCategory());
            info.put("severity", rule.getSeverity());
            info.put("action", rule.getAction());
            info.put("findingCount", ruleFindings.size());
            rulesExecuted.put(rule.getCode(), info);

            for (AuditFinding f : ruleFindings) {
                f.setAuditId(report.getId());
                String evidenceJson = f.getEvidence();
                String h = signatureUtil.chainHash(evidenceJson, null);
                f.setPayloadHash(h);
                f.setSignature(signatureUtil.sign(h));
                findingRepo.save(f);

                AuditSummary.FindingVo vo = new AuditSummary.FindingVo();
                vo.setId(f.getId());
                vo.setRuleCode(f.getRuleCode());
                vo.setRuleName(rule.getName());
                vo.setSeverity(f.getSeverity());
                vo.setAction(f.getAction());
                vo.setTimeRangeStart(f.getTimeRangeStart() == null ? null : f.getTimeRangeStart().toString());
                vo.setTimeRangeEnd(f.getTimeRangeEnd() == null ? null : f.getTimeRangeEnd().toString());
                vo.setAffectedQty(f.getAffectedQty());
                vo.setTemperatureMin(f.getTemperatureMin() == null ? null : f.getTemperatureMin().toPlainString());
                vo.setTemperatureMax(f.getTemperatureMax() == null ? null : f.getTemperatureMax().toPlainString());
                vo.setDescription(f.getDescription());
                vo.setEvidence(f.getEvidence());
                findings.add(vo);

                if (Constants.SEV_CRITICAL.equals(f.getSeverity())) critical++;
                else if (Constants.SEV_MAJOR.equals(f.getSeverity())) major++;
                else minor++;
                if (Constants.ACTION_BLOCK.equals(f.getAction())) block++;
                else if (Constants.ACTION_REVIEW.equals(f.getAction())) review++;
            }
        }

        report.setFinishedAt(ClockUtil.nowUtc());
        long durationMs = Duration.between(report.getStartedAt(), report.getFinishedAt()).toMillis();
        report.setFindingCount(findings.size());
        String status;
        if (block > 0) status = Constants.AUDIT_BLOCK;
        else if (review > 0) status = Constants.AUDIT_REVIEW;
        else status = Constants.AUDIT_PASS;
        report.setStatus(status);

        AuditSummary summary = new AuditSummary();
        summary.setAuditId(report.getId());
        summary.setTaskId(taskId);
        summary.setStatus(status);
        summary.setTotalFindings(findings.size());
        summary.setCriticalCount(critical);
        summary.setMajorCount(major);
        summary.setMinorCount(minor);
        summary.setBlockCount(block);
        summary.setReviewCount(review);
        summary.setDurationMs(durationMs);
        summary.setFindings(findings);
        summary.setRulesExecuted(rulesExecuted);

        String payload = JsonUtil.toJson(summary);
        String payloadHash = signatureUtil.sha256Hex(payload);
        String sig = signatureUtil.sign(payloadHash);
        report.setPayload(payload);
        report.setPayloadHash(payloadHash);
        report.setSignature(sig);
        auditRepo.save(report);
        summary.setPayloadHash(payloadHash);
        summary.setSignature(sig);

        log.info("【规则-审计】完成 task={} status={} findings={} duration={}ms",
                task.getTaskNo(), status, findings.size(), durationMs);
        return summary;
    }

    private String detectDominantForm(List<DrugBatch> batches) {
        if (batches.isEmpty()) return Constants.FORM_COLD;
        Set<String> forms = new HashSet<>();
        for (DrugBatch b : batches) forms.add(b.getDosageForm());
        if (forms.contains(Constants.FORM_FROZEN)) return Constants.FORM_FROZEN;
        if (forms.contains(Constants.FORM_COLD)) return Constants.FORM_COLD;
        return Constants.FORM_NORMAL;
    }

    // ================= 6 类规则 =================

    private List<AuditFinding> checkContinuity(TransportTask task, List<TempSample> samples, ComplianceRule rule) {
        List<AuditFinding> out = new ArrayList<>();
        if (samples.size() < 2) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        long maxGapSec = ((Number) params.getOrDefault("max_gap_sec", 300)).longValue();

        // 多设备冗余架构：按 deviceNo 分组隔离，避免主备设备切换时刻被误判为中断。
        // 仅当同一设备内相邻样本时间间隔 > 阈值时，才出具 CONTINUITY finding。
        Map<String, List<TempSample>> byDev = new LinkedHashMap<>();
        for (TempSample s : samples) {
            List<TempSample> list = byDev.get(s.getDeviceNo());
            if (list == null) {
                list = new ArrayList<>();
                byDev.put(s.getDeviceNo(), list);
            }
            list.add(s);
        }
        for (Map.Entry<String, List<TempSample>> e : byDev.entrySet()) {
            List<TempSample> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(TempSample::getSampleAt));
            if (sorted.size() < 2) continue;
            for (int i = 1; i < sorted.size(); i++) {
                Duration gap = Duration.between(sorted.get(i - 1).getSampleAt(), sorted.get(i).getSampleAt());
                if (gap.getSeconds() > maxGapSec) {
                    AuditFinding f = new AuditFinding();
                    f.setRuleCode(rule.getCode());
                    f.setSeverity(rule.getSeverity());
                    f.setAction(rule.getAction());
                    f.setTimeRangeStart(sorted.get(i - 1).getSampleAt());
                    f.setTimeRangeEnd(sorted.get(i).getSampleAt());
                    f.setAffectedQty(0);
                    f.setDescription(String.format("温控数据连续性中断：%d 秒（阈值 %d 秒）",
                            gap.getSeconds(), maxGapSec));
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("deviceNo", e.getKey());
                    evidence.put("prevSampleAt", sorted.get(i - 1).getSampleAt().toString());
                    evidence.put("nextSampleAt", sorted.get(i).getSampleAt().toString());
                    evidence.put("gapSec", gap.getSeconds());
                    evidence.put("maxGapSec", maxGapSec);
                    f.setEvidence(JsonUtil.toJson(evidence));
                    out.add(f);
                }
            }
        }
        return out;
    }

    private List<AuditFinding> checkCumulative(TransportTask task, List<TempSample> samples, ComplianceRule rule) {
        List<AuditFinding> out = new ArrayList<>();
        if (samples.isEmpty()) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        long maxAboveMin = ((Number) params.getOrDefault("max_above_minutes", 30)).longValue();
        long maxBelowMin = ((Number) params.getOrDefault("max_below_minutes", 30)).longValue();
        BigDecimal low = new BigDecimal(((Number) params.getOrDefault("min_c", 2.0)).doubleValue());
        BigDecimal high = new BigDecimal(((Number) params.getOrDefault("max_c", 8.0)).doubleValue());

        long aboveSec = 0, belowSec = 0;
        OffsetDateTime aboveStart = null, belowStart = null;
        List<Map<String, Object>> aboveEvid = new ArrayList<>(), belowEvid = new ArrayList<>();

        OffsetDateTime lastAt = samples.get(samples.size() - 1).getSampleAt();
        for (int i = 0; i < samples.size(); i++) {
            TempSample s = samples.get(i);
            BigDecimal t = s.getTemperature();
            long dt = 0;
            if (i > 0) dt = Duration.between(samples.get(i - 1).getSampleAt(), s.getSampleAt()).getSeconds();
            if (t.compareTo(high) > 0) {
                if (aboveStart == null) aboveStart = s.getSampleAt();
                aboveSec += dt;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("t", t.toPlainString());
                e.put("at", s.getSampleAt().toString());
                aboveEvid.add(e);
            } else {
                if (aboveStart != null && aboveSec / 60 >= 1) {
                    maybeEmitCumulative(out, rule, task, "above", aboveStart, s.getSampleAt(),
                            aboveSec, aboveEvid, maxAboveMin);
                }
                aboveStart = null; aboveSec = 0; aboveEvid.clear();
            }
            if (t.compareTo(low) < 0) {
                if (belowStart == null) belowStart = s.getSampleAt();
                belowSec += dt;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("t", t.toPlainString());
                e.put("at", s.getSampleAt().toString());
                belowEvid.add(e);
            } else {
                if (belowStart != null && belowSec / 60 >= 1) {
                    maybeEmitCumulative(out, rule, task, "below", belowStart, s.getSampleAt(),
                            belowSec, belowEvid, maxBelowMin);
                }
                belowStart = null; belowSec = 0; belowEvid.clear();
            }
        }
        // 兜底：循环结束时若末段仍处于超温/低温状态且持续 >= 1 分钟，
        // 需以最后一条样本时间作为 end 触发 finding，否则末段 finding 永远丢失。
        if (aboveStart != null && aboveSec / 60 >= 1) {
            maybeEmitCumulative(out, rule, task, "above", aboveStart, lastAt,
                    aboveSec, aboveEvid, maxAboveMin);
        }
        if (belowStart != null && belowSec / 60 >= 1) {
            maybeEmitCumulative(out, rule, task, "below", belowStart, lastAt,
                    belowSec, belowEvid, maxBelowMin);
        }
        return out;
    }

    private void maybeEmitCumulative(List<AuditFinding> out, ComplianceRule rule, TransportTask task,
                                     String direction, OffsetDateTime start, OffsetDateTime end,
                                     long sec, List<Map<String, Object>> ev, long maxMin) {
        long min = sec / 60;
        if (min < maxMin) return;
        AuditFinding f = new AuditFinding();
        f.setRuleCode(rule.getCode());
        f.setSeverity(rule.getSeverity());
        f.setAction(rule.getAction());
        f.setTimeRangeStart(start);
        f.setTimeRangeEnd(end);
        f.setAffectedQty(0);
        f.setDescription(String.format("累积超温（%s）持续 %d 分钟（阈值 %d 分钟）", direction, min, maxMin));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("direction", direction);
        evidence.put("durationMin", min);
        evidence.put("maxMin", maxMin);
        evidence.put("samples", ev.size() > 50 ? ev.subList(0, 50) : ev);
        f.setEvidence(JsonUtil.toJson(evidence));
        out.add(f);
    }

    private List<AuditFinding> checkRange(TransportTask task, List<TempSample> samples, ComplianceRule rule, String form) {
        List<AuditFinding> out = new ArrayList<>();
        if (samples.isEmpty()) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        BigDecimal low = new BigDecimal(((Number) params.getOrDefault("min_c", 2.0)).doubleValue());
        BigDecimal high = new BigDecimal(((Number) params.getOrDefault("max_c", 8.0)).doubleValue());

        for (TempSample s : samples) {
            BigDecimal t = s.getTemperature();
            if (t.compareTo(high) > 0 || t.compareTo(low) < 0) {
                AuditFinding f = new AuditFinding();
                f.setRuleCode(rule.getCode());
                f.setSeverity(Constants.SEV_CRITICAL);
                f.setAction(Constants.ACTION_BLOCK);
                f.setTimeRangeStart(s.getSampleAt());
                f.setTimeRangeEnd(s.getSampleAt());
                f.setAffectedQty(0);
                f.setTemperatureMin(t);
                f.setTemperatureMax(t);
                f.setDescription(String.format("瞬时温度越界：%s℃（剂型 %s 区间 [%.1f, %.1f]）",
                        t.toPlainString(), form, low.doubleValue(), high.doubleValue()));
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("deviceNo", s.getDeviceNo());
                evidence.put("sampleAt", s.getSampleAt().toString());
                evidence.put("temperature", t.toPlainString());
                evidence.put("limitLow", low.toPlainString());
                evidence.put("limitHigh", high.toPlainString());
                f.setEvidence(JsonUtil.toJson(evidence));
                out.add(f);
            }
        }
        return out;
    }

    private List<AuditFinding> checkDoor(TransportTask task, List<TempSample> samples, ComplianceRule rule) {
        List<AuditFinding> out = new ArrayList<>();
        if (samples.isEmpty()) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        int maxPerHour = ((Number) params.getOrDefault("max_open_count_per_hour", 4)).intValue();
        int maxDurSec = ((Number) params.getOrDefault("max_open_duration_sec", 180)).intValue();

        Map<String, List<TempSample>> byDev = new HashMap<>();
        for (TempSample s : samples) {
            List<TempSample> list = byDev.get(s.getDeviceNo());
            if (list == null) {
                list = new ArrayList<>();
                byDev.put(s.getDeviceNo(), list);
            }
            list.add(s);
        }
        for (Map.Entry<String, List<TempSample>> e : byDev.entrySet()) {
            List<TempSample> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing(TempSample::getSampleAt));
            int i = 0, openInWindow = 0;
            for (int j = 0; j < list.size(); j++) {
                while (i < j && Duration.between(list.get(i).getSampleAt(), list.get(j).getSampleAt()).toHours() >= 1) {
                    if (Boolean.TRUE.equals(list.get(i).getDoorOpen())) openInWindow--;
                    i++;
                }
                if (Boolean.TRUE.equals(list.get(j).getDoorOpen())) openInWindow++;
                if (openInWindow > maxPerHour) {
                    AuditFinding f = new AuditFinding();
                    f.setRuleCode(rule.getCode());
                    f.setSeverity(rule.getSeverity());
                    f.setAction(rule.getAction());
                    f.setTimeRangeStart(list.get(Math.max(i, 0)).getSampleAt());
                    f.setTimeRangeEnd(list.get(j).getSampleAt());
                    f.setAffectedQty(0);
                    f.setDescription(String.format("门开关频次过高：1 小时内 %d 次（阈值 %d）",
                            openInWindow, maxPerHour));
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("deviceNo", e.getKey());
                    evidence.put("openCountIn1h", openInWindow);
                    evidence.put("threshold", maxPerHour);
                    f.setEvidence(JsonUtil.toJson(evidence));
                    out.add(f);
                    break;
                }
            }
            OffsetDateTime openStart = null;
            for (TempSample s : list) {
                if (Boolean.TRUE.equals(s.getDoorOpen())) {
                    if (openStart == null) openStart = s.getSampleAt();
                } else {
                    if (openStart != null) {
                        long dur = Duration.between(openStart, s.getSampleAt()).getSeconds();
                        if (dur > maxDurSec) {
                            AuditFinding f = new AuditFinding();
                            f.setRuleCode(rule.getCode());
                            f.setSeverity(rule.getSeverity());
                            f.setAction(rule.getAction());
                            f.setTimeRangeStart(openStart);
                            f.setTimeRangeEnd(s.getSampleAt());
                            f.setAffectedQty(0);
                            f.setDescription(String.format("门持续开启 %d 秒（阈值 %d 秒）", dur, maxDurSec));
                            Map<String, Object> evidence = new LinkedHashMap<>();
                            evidence.put("deviceNo", s.getDeviceNo());
                            evidence.put("durationSec", dur);
                            evidence.put("threshold", maxDurSec);
                            f.setEvidence(JsonUtil.toJson(evidence));
                            out.add(f);
                        }
                        openStart = null;
                    }
                }
            }
        }
        return out;
    }

    private List<AuditFinding> checkTrack(TransportTask task, List<TrackPoint> tracks, ComplianceRule rule) {
        List<AuditFinding> out = new ArrayList<>();
        if (tracks.size() < 2) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        double maxDevKm = ((Number) params.getOrDefault("max_deviation_km", 50.0)).doubleValue();

        TrackPoint first = tracks.get(0);
        TrackPoint last = tracks.get(tracks.size() - 1);
        double maxDev = 0;
        TrackPoint worst = null;
        for (TrackPoint p : tracks) {
            double d = pointToLineDistanceKm(
                    first.getLatitude().doubleValue(), first.getLongitude().doubleValue(),
                    last.getLatitude().doubleValue(), last.getLongitude().doubleValue(),
                    p.getLatitude().doubleValue(), p.getLongitude().doubleValue());
            if (d > maxDev) { maxDev = d; worst = p; }
        }
        if (maxDev > maxDevKm) {
            AuditFinding f = new AuditFinding();
            f.setRuleCode(rule.getCode());
            f.setSeverity(rule.getSeverity());
            f.setAction(rule.getAction());
            f.setTimeRangeStart(first.getSampleAt());
            f.setTimeRangeEnd(last.getSampleAt());
            f.setAffectedQty(0);
            f.setDescription(String.format("轨迹偏移 %.2f km（阈值 %.2f km）", maxDev, maxDevKm));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("maxDeviationKm", round2(maxDev));
            evidence.put("threshold", maxDevKm);
            evidence.put("worstAt", worst == null ? null : worst.getSampleAt().toString());
            evidence.put("worstLat", worst == null ? null : worst.getLatitude().toPlainString());
            evidence.put("worstLng", worst == null ? null : worst.getLongitude().toPlainString());
            f.setEvidence(JsonUtil.toJson(evidence));
            out.add(f);
        }
        return out;
    }

    private List<AuditFinding> checkSmoothness(TransportTask task, List<TempSample> samples, ComplianceRule rule) {
        List<AuditFinding> out = new ArrayList<>();
        if (samples.size() < 3) return out;
        Map<String, Object> params = JsonUtil.toMap(rule.getExpression());
        double maxRate = ((Number) params.getOrDefault("max_rate_per_min", 2.0)).doubleValue();

        List<TempSample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparing(TempSample::getSampleAt));
        for (int i = 1; i < sorted.size(); i++) {
            BigDecimal dt = sorted.get(i).getTemperature().subtract(sorted.get(i - 1).getTemperature()).abs();
            long sec = Duration.between(sorted.get(i - 1).getSampleAt(), sorted.get(i).getSampleAt()).getSeconds();
            if (sec == 0) continue;
            double rate = dt.doubleValue() / (sec / 60.0);
            if (rate > maxRate) {
                AuditFinding f = new AuditFinding();
                f.setRuleCode(rule.getCode());
                f.setSeverity(rule.getSeverity());
                f.setAction(rule.getAction());
                f.setTimeRangeStart(sorted.get(i - 1).getSampleAt());
                f.setTimeRangeEnd(sorted.get(i).getSampleAt());
                f.setAffectedQty(0);
                f.setTemperatureMin(sorted.get(i - 1).getTemperature());
                f.setTemperatureMax(sorted.get(i).getTemperature());
                f.setDescription(String.format("温度曲线跳变 %.2f ℃/min（阈值 %.2f）", rate, maxRate));
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("deviceNo", sorted.get(i).getDeviceNo());
                evidence.put("rate", round2(rate));
                evidence.put("threshold", maxRate);
                evidence.put("fromT", sorted.get(i - 1).getTemperature().toPlainString());
                evidence.put("toT", sorted.get(i).getTemperature().toPlainString());
                evidence.put("fromAt", sorted.get(i - 1).getSampleAt().toString());
                evidence.put("toAt", sorted.get(i).getSampleAt().toString());
                f.setEvidence(JsonUtil.toJson(evidence));
                out.add(f);
            }
        }
        return out;
    }

    // ================= 工具 =================

    private double pointToLineDistanceKm(double lat1, double lng1, double lat2, double lng2,
                                          double plat, double plng) {
        double R = 6371.0;
        double toRad = Math.PI / 180.0;
        double x1 = lng1 * toRad * R * Math.cos(lat1 * toRad);
        double y1 = lat1 * toRad * R;
        double x2 = lng2 * toRad * R * Math.cos(lat2 * toRad);
        double y2 = lat2 * toRad * R;
        double x0 = plng * toRad * R * Math.cos(plat * toRad);
        double y0 = plat * toRad * R;
        double dx = x2 - x1, dy = y2 - y1;
        double num = Math.abs(dy * x0 - dx * y0 + x2 * y1 - y2 * x1);
        double den = Math.sqrt(dx * dx + dy * dy);
        if (den == 0) return Math.sqrt((x0 - x1) * (x0 - x1) + (y0 - y1) * (y0 - y1));
        return num / den;
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
