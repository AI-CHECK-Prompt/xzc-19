package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.SegmentHandover;
import com.coldchain.compliance.repository.SegmentHandoverRepository;
import com.coldchain.compliance.repository.TransportSegmentRepository;
import com.coldchain.compliance.service.OperationLogService;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 段间交接管理。
 * <p>
 * 前后两段温控/轨迹/设备数据无缝衔接；is_non_transit=true 时为"非在途段"（如堆场停放），
 * 不计入合规审计但保留可追溯证据。
 */
@RestController
@RequestMapping("/api/handovers")
@RequiredArgsConstructor
public class HandoverController {

    private final SegmentHandoverRepository repo;
    private final TransportSegmentRepository segmentRepo;
    private final OperationLogService opLogService;

    @GetMapping("/by-task/{taskId}")
    public List<Map<String, Object>> listByTask(@PathVariable Long taskId) {
        List<SegmentHandover> list = repo.findByTaskIdOrderByHandoverAtAsc(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SegmentHandover h : list) out.add(toVo(h));
        return out;
    }

    /** 列出所有非在途段（堆场/中转） */
    @GetMapping("/non-transit/{taskId}")
    public List<Map<String, Object>> nonTransit(@PathVariable Long taskId) {
        List<SegmentHandover> list = repo.findByTaskIdAndIsNonTransitTrue(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SegmentHandover h : list) out.add(toVo(h));
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SegmentHandover h, HttpServletRequest req) {
        if (h.getHandoverAt() == null) h.setHandoverAt(ClockUtil.nowUtc());
        if (h.getContinuityOk() == null) h.setContinuityOk(true);
        if (h.getDeviceHandoffOk() == null) h.setDeviceHandoffOk(true);
        if (h.getIsNonTransit() == null) h.setIsNonTransit(false);
        // 自动计算 gap_minutes
        if (h.getFromSegmentId() != null && h.getToSegmentId() != null) {
            segmentRepo.findById(h.getFromSegmentId()).ifPresent(from -> {
                segmentRepo.findById(h.getToSegmentId()).ifPresent(to -> {
                    OffsetDateTime fromEnd = from.getActualArriveAt() == null ? from.getPlannedArriveAt() : from.getActualArriveAt();
                    OffsetDateTime toStart = to.getActualDepartAt() == null ? to.getPlannedDepartAt() : to.getActualDepartAt();
                    if (fromEnd != null && toStart != null) {
                        long min = java.time.Duration.between(fromEnd, toStart).toMinutes();
                        h.setGapMinutes((int) min);
                    }
                });
            });
        }
        SegmentHandover saved = repo.save(h);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "HANDOVER", "HANDOVER",
                String.valueOf(saved.getId()),
                "from=" + saved.getFromSegmentId() + " to=" + saved.getToSegmentId()
                        + " nonTransit=" + saved.getIsNonTransit(),
                "OK", req.getRemoteAddr(), req.getHeader("User-Agent"));
        return toVo(saved);
    }

    private Map<String, Object> toVo(SegmentHandover h) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", h.getId());
        vo.put("taskId", h.getTaskId());
        vo.put("fromSegmentId", h.getFromSegmentId());
        vo.put("toSegmentId", h.getToSegmentId());
        vo.put("handoverAt", h.getHandoverAt());
        vo.put("fromCarrierId", h.getFromCarrierId());
        vo.put("toCarrierId", h.getToCarrierId());
        vo.put("lastTempC", h.getLastTempC());
        vo.put("lastHumidity", h.getLastHumidity());
        vo.put("lastDeviceNo", h.getLastDeviceNo());
        vo.put("continuityOk", h.getContinuityOk());
        vo.put("deviceHandoffOk", h.getDeviceHandoffOk());
        vo.put("gapMinutes", h.getGapMinutes());
        vo.put("isNonTransit", h.getIsNonTransit());
        vo.put("storageLocation", h.getStorageLocation());
        vo.put("storageTemperature", h.getStorageTemperature());
        vo.put("operator", h.getOperator());
        // 关联段信息
        segmentRepo.findById(h.getFromSegmentId()).ifPresent(s -> {
            vo.put("fromSegmentNo", s.getSegmentNo());
            vo.put("fromSegmentType", s.getSegmentType());
        });
        segmentRepo.findById(h.getToSegmentId()).ifPresent(s -> {
            vo.put("toSegmentNo", s.getSegmentNo());
            vo.put("toSegmentType", s.getSegmentType());
        });
        return vo;
    }
}
