package com.coldchain.compliance.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 批量上报请求（HTTP/MQTT 通用）。
 */
@Data
public class IngestRequest {
    private List<TempSampleDto> samples;
    private List<TrackPointDto> tracks;
    private Map<String, Object> metadata;
}
