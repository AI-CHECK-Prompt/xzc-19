package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.CarrierWorkorder;
import com.coldchain.compliance.service.WorkOrderService;
import com.coldchain.compliance.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 承运商整改工单。多式联运协同引擎在某段发生异常时自动开具。
 */
@RestController
@RequestMapping("/api/workorders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService service;

    @GetMapping
    public List<CarrierWorkorder> list(@RequestParam(required = false) String status) {
        if (status != null) return service.listAll().stream()
                .filter(w -> status.equals(w.getStatus())).collect(java.util.stream.Collectors.toList());
        return service.listAll();
    }

    @GetMapping("/by-task/{taskId}")
    public List<CarrierWorkorder> byTask(@PathVariable Long taskId) { return service.listByTask(taskId); }

    @GetMapping("/by-carrier/{carrierId}")
    public List<CarrierWorkorder> byCarrier(@PathVariable Long carrierId) { return service.listByCarrier(carrierId); }

    @GetMapping("/by-segment/{segmentId}")
    public List<CarrierWorkorder> bySegment(@PathVariable Long segmentId) { return service.listBySegment(segmentId); }

    @GetMapping("/{id}")
    public CarrierWorkorder get(@PathVariable Long id) {
        return service.listAll().stream().filter(w -> w.getId().equals(id)).findFirst().orElse(null);
    }

    /** 手工开具工单（一般由协同引擎自动完成） */
    @PostMapping
    public CarrierWorkorder create(@RequestBody Map<String, Object> body) {
        Long taskId = ((Number) body.get("taskId")).longValue();
        String taskNo = (String) body.get("taskNo");
        Long segmentId = ((Number) body.get("segmentId")).longValue();
        Long carrierId = ((Number) body.get("carrierId")).longValue();
        String exceptionType = (String) body.get("exceptionType");
        String severity = (String) body.get("severity");
        String title = (String) body.get("title");
        String description = (String) body.get("description");
        Long prescriptionId = body.get("prescriptionId") == null ? null : ((Number) body.get("prescriptionId")).longValue();
        Integer affectedQty = body.get("affectedQty") == null ? null : ((Number) body.get("affectedQty")).intValue();
        String responsibleParty = (String) body.get("responsibleParty");
        Integer responseHours = body.get("responseHours") == null ? 24 : ((Number) body.get("responseHours")).intValue();
        boolean reg = body.get("regulatoryReport") == null ? false : (boolean) body.get("regulatoryReport");
        return service.create(taskId, taskNo, segmentId, carrierId,
                exceptionType, severity, title, description, prescriptionId,
                affectedQty, responsibleParty, responseHours, reg);
    }

    @PostMapping("/{id}/resolve")
    public CarrierWorkorder resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : (String) body.get("note");
        return service.resolve(id, note);
    }
}
