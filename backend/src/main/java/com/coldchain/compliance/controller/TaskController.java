package com.coldchain.compliance.controller;

import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.service.OperationLogService;
import com.coldchain.compliance.util.ClockUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TransportTaskRepository taskRepo;
    private final OperationLogService opLogService;

    @GetMapping
    public List<TransportTask> list(@RequestParam(required = false) String status) {
        return status == null ? taskRepo.findAll() : taskRepo.findByStatus(status);
    }

    @GetMapping("/{taskNo}")
    public TransportTask get(@PathVariable String taskNo) {
        return taskRepo.findByTaskNo(taskNo).orElse(null);
    }

    @PostMapping
    public TransportTask create(@RequestBody TransportTask t, HttpServletRequest req) {
        t.setStatus(t.getStatus() == null ? "CREATED" : t.getStatus());
        t.setUpdatedAt(ClockUtil.nowUtc());
        if (t.getDepartureAt() == null) t.setDepartureAt(ClockUtil.nowUtc());
        TransportTask saved = taskRepo.save(t);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "CREATE_TASK", "TASK",
                saved.getTaskNo(), "task=" + saved.getTaskNo(), "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }

    @PostMapping("/{taskNo}/depart")
    public TransportTask depart(@PathVariable String taskNo, HttpServletRequest req) {
        TransportTask t = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskNo));
        t.setStatus("IN_TRANSIT");
        t.setDepartureAt(ClockUtil.nowUtc());
        t.setUpdatedAt(t.getDepartureAt());
        TransportTask saved = taskRepo.save(t);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "DEPART", "TASK",
                taskNo, "task=" + taskNo, "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }

    @PostMapping("/{taskNo}/arrive")
    public TransportTask arrive(@PathVariable String taskNo, HttpServletRequest req) {
        TransportTask t = taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskNo));
        t.setStatus("ARRIVED");
        t.setArrivalAt(ClockUtil.nowUtc());
        t.setUpdatedAt(t.getArrivalAt());
        TransportTask saved = taskRepo.save(t);
        opLogService.logAsync("DISPATCHER", "DISPATCHER", "ARRIVE", "TASK",
                taskNo, "task=" + taskNo, "OK",
                req.getRemoteAddr(), req.getHeader("User-Agent"));
        return saved;
    }
}
