package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.ReplayResponse;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 飞检审计回放：任意一笔运输任务的完整数据回放。
 * <p>
 * 包含：温度曲线、轨迹、合规检查点、决策依据、责任人操作日志。
 * 同时执行哈希链校验，输出完整性结论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final TransportTaskRepository taskRepo;
    private final TempSampleRepository sampleRepo;
    private final TrackPointRepository trackRepo;
    private final AuditReportRepository auditRepo;
    private final AuditFindingRepository findingRepo;
    private final ReleaseDecisionRepository decisionRepo;
    private final OperationLogRepository opLogRepo;
    private final SignatureUtil signatureUtil;

    public ReplayResponse replay(String taskNo) {
        TransportTask task = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskNo));

        List<TempSample> samples = sampleRepo.findByTaskIdOrderBySampleAt(task.getId());
        List<TrackPoint> tracks = trackRepo.findByTaskIdOrderBySampleAt(task.getId());
        List<AuditReport> audits = auditRepo.findByTaskIdOrderByStartedAtDesc(task.getId());
        List<ReleaseDecision> decisions = decisionRepo.findByTaskIdOrderByDecidedAtDesc(task.getId());

        ReplayResponse r = new ReplayResponse();
        r.setTaskNo(taskNo);
        r.setStartTime(samples.isEmpty() ? task.getDepartureAt() : samples.get(0).getSampleAt());
        r.setEndTime(samples.isEmpty() ? task.getArrivalAt() : samples.get(samples.size() - 1).getSampleAt());

        // 温度曲线
        List<Map<String, Object>> curve = new ArrayList<>();
        for (TempSample s : samples) {
            curve.add(mapOf("at", s.getSampleAt().toString(),
                    "deviceNo", s.getDeviceNo(),
                    "temperature", s.getTemperature().toPlainString(),
                    "humidity", s.getHumidity() == null ? null : s.getHumidity().toPlainString(),
                    "doorOpen", s.getDoorOpen(),
                    "hash", s.getPayloadHash()));
        }
        r.setTemperatureCurve(curve);

        // 轨迹
        List<Map<String, Object>> tp = new ArrayList<>();
        for (TrackPoint p : tracks) {
            tp.add(mapOf("at", p.getSampleAt().toString(),
                    "deviceNo", p.getDeviceNo(),
                    "lat", p.getLatitude().toPlainString(),
                    "lng", p.getLongitude().toPlainString(),
                    "speed", p.getSpeedKmh() == null ? null : p.getSpeedKmh().toPlainString(),
                    "hash", p.getPayloadHash()));
        }
        r.setTrackPoints(tp);

        // 审计
        Map<String, Object> auditSummary = new LinkedHashMap<>();
        List<Map<String, Object>> auditList = new ArrayList<>();
        for (AuditReport a : audits) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("auditId", a.getId());
            am.put("status", a.getStatus());
            am.put("startedAt", a.getStartedAt() == null ? null : a.getStartedAt().toString());
            am.put("finishedAt", a.getFinishedAt() == null ? null : a.getFinishedAt().toString());
            am.put("findingCount", a.getFindingCount());
            am.put("payloadHash", a.getPayloadHash());
            am.put("signature", a.getSignature());
            List<Map<String, Object>> fs = new ArrayList<>();
            for (AuditFinding f : findingRepo.findByAuditId(a.getId())) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("id", f.getId());
                fm.put("ruleCode", f.getRuleCode());
                fm.put("severity", f.getSeverity());
                fm.put("action", f.getAction());
                fm.put("timeRange",
                        (f.getTimeRangeStart() == null ? "" : f.getTimeRangeStart().toString())
                        + " ~ " + (f.getTimeRangeEnd() == null ? "" : f.getTimeRangeEnd().toString()));
                fm.put("description", f.getDescription());
                fm.put("evidence", JsonUtil.toMap(f.getEvidence()));
                fs.add(fm);
            }
            am.put("findings", fs);
            auditList.add(am);
        }
        auditSummary.put("audits", auditList);
        auditSummary.put("latestStatus", audits.isEmpty() ? null : audits.get(0).getStatus());
        r.setAuditSummary(auditSummary);

        // 决策
        List<Map<String, Object>> dl = new ArrayList<>();
        for (ReleaseDecision d : decisions) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.getId());
            dm.put("decision", d.getDecision());
            dm.put("decidedBy", d.getDecidedBy());
            dm.put("decidedAt", d.getDecidedAt() == null ? null : d.getDecidedAt().toString());
            dm.put("temperatureOk", d.getTemperatureOk());
            dm.put("customsOk", d.getCustomsOk());
            dm.put("inspectionOk", d.getInspectionOk());
            dm.put("comment", d.getComment());
            dm.put("payloadHash", d.getPayloadHash());
            dm.put("signature", d.getSignature());
            dm.put("basis", d.getBasis() == null ? null : JsonUtil.toMap(d.getBasis()));
            dl.add(dm);
        }
        r.setDecisions(dl);

        // 操作日志（按 task 资源过滤）
        List<Map<String, Object>> ol = new ArrayList<>();
        opLogRepo.findAll().stream()
                .filter(o -> taskNo.equals(o.getResourceId()))
                .sorted(Comparator.comparing(OperationLog::getId))
                .forEach(o -> {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("id", o.getId());
                    om.put("user", o.getUserId());
                    om.put("role", o.getUserRole());
                    om.put("action", o.getAction());
                    om.put("result", o.getResult());
                    om.put("at", o.getOccurredAt() == null ? null : o.getOccurredAt().toString());
                    om.put("payload", o.getPayload());
                    om.put("hash", o.getPayloadHash());
                    om.put("signature", o.getSignature());
                    ol.add(om);
                });
        r.setOperationLogs(ol);

        // 整合时间轴（按时间排序所有事件）
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (TempSample s : samples) {
            timeline.add(mapOf("type", "TEMP", "at", s.getSampleAt().toString(),
                    "data", s.getTemperature().toPlainString(), "hash", s.getPayloadHash()));
        }
        for (TrackPoint p : tracks) {
            timeline.add(mapOf("type", "TRACK", "at", p.getSampleAt().toString(),
                    "data", p.getLatitude().toPlainString() + "," + p.getLongitude().toPlainString(),
                    "hash", p.getPayloadHash()));
        }
        for (AuditReport a : audits) {
            timeline.add(mapOf("type", "AUDIT", "at",
                    a.getStartedAt() == null ? "" : a.getStartedAt().toString(),
                    "data", a.getStatus(), "hash", a.getPayloadHash()));
        }
        for (ReleaseDecision d : decisions) {
            timeline.add(mapOf("type", "DECISION", "at",
                    d.getDecidedAt() == null ? "" : d.getDecidedAt().toString(),
                    "data", d.getDecision(), "hash", d.getPayloadHash()));
        }
        timeline.sort(Comparator.comparing(m -> String.valueOf(m.get("at"))));
        r.setTimeline(timeline);

        // 完整性校验
        r.setIntegrityCheck(verifyIntegrity(samples, tracks, opLogRepo.findAll()));

        log.info("【回放-飞检】task={} samples={} tracks={} audits={} decisions={} logs={}",
                taskNo, samples.size(), tracks.size(), audits.size(), decisions.size(), ol.size());
        return r;
    }

    private Map<String, Object> verifyIntegrity(List<TempSample> samples, List<TrackPoint> tracks,
                                                 List<OperationLog> logs) {
        Map<String, Object> r = new LinkedHashMap<>();
        int ok = 0, bad = 0;
        // 校验温控采样哈希链
        Map<String, String> prev = new HashMap<>();
        for (TempSample s : samples) {
            String ph = prev.get(s.getDeviceNo());
            boolean hashOk = (ph == null && s.getPrevHash() == null)
                    || (ph != null && ph.equals(s.getPrevHash()));
            boolean sigOk = signatureUtil.verify(s.getPayloadHash(), s.getSignature());
            if (hashOk && sigOk) ok++;
            else bad++;
            prev.put(s.getDeviceNo(), s.getPayloadHash());
        }
        // 校验轨迹链
        prev.clear();
        for (TrackPoint p : tracks) {
            String ph = prev.get(p.getDeviceNo());
            boolean hashOk = (ph == null && p.getPrevHash() == null)
                    || (ph != null && ph.equals(p.getPrevHash()));
            boolean sigOk = signatureUtil.verify(p.getPayloadHash(), p.getSignature());
            if (hashOk && sigOk) ok++;
            else bad++;
            prev.put(p.getDeviceNo(), p.getPayloadHash());
        }
        // 校验操作日志链
        List<OperationLog> sortedLogs = new ArrayList<>(logs);
        sortedLogs.sort(Comparator.comparing(OperationLog::getId));
        for (OperationLog o : sortedLogs) {
            if (o.getSignature() == null) continue;
            boolean sigOk = signatureUtil.verify(o.getPayloadHash(), o.getSignature());
            if (sigOk) ok++;
            else bad++;
        }
        r.put("totalChecked", ok + bad);
        r.put("passed", ok);
        r.put("failed", bad);
        r.put("integrity", bad == 0 ? "INTACT" : "BROKEN");
        return r;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
