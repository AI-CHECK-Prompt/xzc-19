package com.coldchain.compliance.controller;

import com.coldchain.compliance.dto.IngestRequest;
import com.coldchain.compliance.dto.IngestResponse;
import com.coldchain.compliance.service.IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

/**
 * 数据接入：设备通过 HTTP POST 上报。
 * MQTT 接入：simulator/ingest-mqtt.py 通过 EMQX → 同一接口
 */
@Slf4j
@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    @PostMapping
    public IngestResponse ingest(@RequestBody IngestRequest req, HttpServletRequest httpReq) {
        return ingestService.ingest(req);
    }
}
