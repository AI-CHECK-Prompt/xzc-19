package com.coldchain.compliance.controller;

import com.coldchain.compliance.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 模拟器触发：从后端直接拉起 Python 模拟器子进程。
 * 飞检演示场景下，不需要用户额外开终端。
 */
@Slf4j
@RestController
@RequestMapping("/api/sim")
public class SimulatorController {

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> cfg) {
        int devices = intVal(cfg.get("devices"), 100);
        int ticks = intVal(cfg.get("ticks"), 20);
        int workers = intVal(cfg.get("workers"), 20);
        int overheat = intVal(cfg.get("overheat"), 0);

        log.info("【模拟器】启动 devices={} ticks={} workers={} overheat={}",
                devices, ticks, workers, overheat);

        try {
            // 优先尝试 python3，其次 python
            String[] cmds;
            if (new java.io.File("simulator/simulator.py").exists()) {
                cmds = new String[]{"python", "simulator/simulator.py",
                    "--devices", String.valueOf(devices),
                    "--ticks", String.valueOf(ticks),
                    "--workers", String.valueOf(workers),
                    "--inject-overheat-devices", String.valueOf(overheat)};
            } else if (new java.io.File("simulator.py").exists()) {
                cmds = new String[]{"python", "simulator.py",
                    "--devices", String.valueOf(devices),
                    "--ticks", String.valueOf(ticks),
                    "--workers", String.valueOf(workers),
                    "--inject-overheat-devices", String.valueOf(overheat)};
            } else {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("ok", false);
                err.put("message", "simulator.py 不存在");
                return err;
            }
            ProcessBuilder pb = new ProcessBuilder(cmds);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            int exit = p.waitFor();

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", exit == 0);
            r.put("exitCode", exit);
            r.put("log", sb.toString());
            r.put("devices", devices);
            r.put("ticks", ticks);
            log.info("【模拟器】完成 exit={} log-lines={}", exit, sb.toString().split("\n").length);
            return r;
        } catch (Exception e) {
            log.error("【模拟器】失败", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", e.getMessage());
            return err;
        }
    }

    private int intVal(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }
}
