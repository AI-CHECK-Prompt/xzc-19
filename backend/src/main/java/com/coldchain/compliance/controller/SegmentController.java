package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.service.OperationLogService;
import com.coldchain.compliance.util.ClockUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 运输段管理（多式联运的"段"）。
 * <p>
 * 一笔订单可拆 N 段，每段独立指定承运商、起止地、温控、责任人。
 * is_in_transit=false 标记为非在途段（堆场/中转），不计入合规审计。
 */
@RestController
@RequestMapping("/api/segments")
@RequiredArgsConstructor
public class SegmentController {

    private final TransportSegmentRepository repo;
    private final TransportTaskRepository taskRepo;
    private final CarrierRepository carrierRepo;
    private final OperationLogService opLogService;

    /** 列出一笔订单的所有段（按 seq 升序） */
    @GetMapping("/by-task/{taskId}")
    public List<TransportSegment> listByTask(@PathVariable Long taskId) {
        return repo.findByTaskIdOrderBySeqAsc(taskId);
    }

    /** 列出一笔订单的所有在途段 */
    @GetMapping("/in-transit/{taskId}")
    public List<TransportSegment> listInTransit(@PathVariable Long taskId) {
        return repo.findByTaskIdAndIsInTransitTrue(taskId);
    }

    @GetMapping("/{id}")
    public TransportSegment get(@PathVariable Long id) {
        return repo.findById(id).orElse(null);
    }

    /** 创建一段（一次可建 N 段） */
    @PostMapping
    public TransportSegment create(@RequestBody TransportSegment seg, HttpServletRequest req) {
        if (seg.getStatus() == null) seg.setStatus("PLANNED");
        if (seg.getIsInTransit() == null) seg.setIsInTransit(true);
        TransportSegment saved = repo.save(seg);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "CREATE_SEGMENT", "SEGMENT",
                saved.getSegmentNo(), "task=" + saved.getTaskId() + " type=" + saved.getSegmentType(),
                "OK", req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }

    /** 批量创建（一笔订单一次建多段） */
    @PostMapping("/batch")
    public List<TransportSegment> createBatch(@RequestBody List<TransportSegment> segs, HttpServletRequest req) {
        List<TransportSegment> out = new ArrayList<>();
        for (TransportSegment s : segs) {
            if (s.getStatus() == null) s.setStatus("PLANNED");
            if (s.getIsInTransit() == null) s.setIsInTransit(true);
            out.add(repo.save(s));
        }
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "CREATE_SEGMENTS_BATCH", "TASK",
                "batch", "count=" + out.size(), "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return out;
    }

    /** 段发车 */
    @PostMapping("/{id}/depart")
    public TransportSegment depart(@PathVariable Long id, HttpServletRequest req) {
        TransportSegment s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("段不存在"));
        s.setActualDepartAt(ClockUtil.nowUtc());
        s.setStatus("DEPARTED");
        TransportSegment saved = repo.save(s);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "SEGMENT_DEPART", "SEGMENT",
                saved.getSegmentNo(), "id=" + id, "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }

    /** 段到达 */
    @PostMapping("/{id}/arrive")
    public TransportSegment arrive(@PathVariable Long id, HttpServletRequest req) {
        TransportSegment s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("段不存在"));
        s.setActualArriveAt(ClockUtil.nowUtc());
        s.setStatus("ARRIVED");
        TransportSegment saved = repo.save(s);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "SEGMENT_ARRIVE", "SEGMENT",
                saved.getSegmentNo(), "id=" + id, "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }

    /** 全量运输段树（含承运商名称、温控曲线） */
    @GetMapping("/tree/{taskId}")
    public Map<String, Object> tree(@PathVariable Long taskId) {
        List<TransportSegment> segs = repo.findByTaskIdOrderBySeqAsc(taskId);
        TransportTask task = taskRepo.findById(taskId).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TransportSegment s : segs) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", s.getId());
            vo.put("segmentNo", s.getSegmentNo());
            vo.put("segmentType", s.getSegmentType());
            vo.put("origin", s.getOrigin());
            vo.put("destination", s.getDestination());
            vo.put("carrierId", s.getCarrierId());
            vo.put("transportTool", s.getTransportTool());
            vo.put("toolNo", s.getToolNo());
            vo.put("temperatureMin", s.getTemperatureMin());
            vo.put("temperatureMax", s.getTemperatureMax());
            vo.put("isInTransit", s.getIsInTransit());
            vo.put("status", s.getStatus());
            vo.put("responsiblePerson", s.getResponsiblePerson());
            vo.put("responsiblePhone", s.getResponsiblePhone());
            vo.put("seq", s.getSeq());
            vo.put("plannedDepartAt", s.getPlannedDepartAt());
            vo.put("plannedArriveAt", s.getPlannedArriveAt());
            vo.put("actualDepartAt", s.getActualDepartAt());
            vo.put("actualArriveAt", s.getActualArriveAt());
            vo.put("remark", s.getRemark());
            if (s.getCarrierId() != null) {
                Carrier c = carrierRepo.findById(s.getCarrierId()).orElse(null);
                if (c != null) {
                    vo.put("carrierName", c.getCarrierName());
                    vo.put("carrierCode", c.getCarrierCode());
                    vo.put("carrierType", c.getCarrierType());
                }
            }
            out.add(vo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("taskNo", task == null ? null : task.getTaskNo());
        result.put("origin", task == null ? null : task.getOrigin());
        result.put("destination", task == null ? null : task.getDestination());
        result.put("segmentCount", out.size());
        result.put("inTransitCount", out.stream()
                .filter(v -> Boolean.TRUE.equals(v.get("isInTransit"))).count());
        result.put("nonTransitCount", out.stream()
                .filter(v -> Boolean.FALSE.equals(v.get("isInTransit"))).count());
        result.put("segments", out);
        return result;
    }
}
