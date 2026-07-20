package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.IngestRequest;
import com.coldchain.compliance.dto.IngestResponse;
import com.coldchain.compliance.dto.TempSampleDto;
import com.coldchain.compliance.dto.TrackPointDto;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.ClockUtil;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 数据接入服务：温控/轨迹采样 → 时钟对齐 → 哈希链 → RSA 签名 → WORM 写入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final TempSampleRepository sampleRepo;
    private final TrackPointRepository trackRepo;
    private final TransportTaskRepository taskRepo;
    private final TempRecorderRepository recorderRepo;
    private final SignatureUtil signatureUtil;
    private final OperationLogService opLogService;

    @Transactional
    public IngestResponse ingest(IngestRequest req) {
        long t0 = System.currentTimeMillis();
        IngestResponse resp = new IngestResponse();
        resp.setSampleAccepted(0);
        resp.setSampleRejected(0);
        resp.setTrackAccepted(0);
        resp.setTrackRejected(0);

        // 1) 温控采样
        if (req.getSamples() != null) {
            Map<String, Long> lastSeq = new HashMap<>();
            Map<String, String> lastHash = new HashMap<>();
            for (TempSampleDto dto : req.getSamples()) {
                try {
                    TempRecorder r = recorderRepo.findByDeviceNo(dto.getDeviceNo()).orElse(null);
                    long skew = (r == null) ? 0L : r.getClockSkewMs();
                    Long taskId = null;
                    if (dto.getTaskNo() != null) {
                        taskId = taskRepo.findByTaskNo(dto.getTaskNo())
                                .map(TransportTask::getId).orElse(null);
                    }
                    OffsetDateTime aligned = ClockUtil.align(dto.getSampleAt(), skew);

                    Long expected = lastSeq.getOrDefault(dto.getDeviceNo(), 0L) + 1;
                    if (dto.getSeqNo() != null && dto.getSeqNo() < expected) {
                        resp.setSampleRejected(resp.getSampleRejected() + 1);
                        log.warn("【接入-采样】序号过期: device={} seq={} expected>={}",
                                dto.getDeviceNo(), dto.getSeqNo(), expected);
                        continue;
                    }
                    if (dto.getSeqNo() != null) lastSeq.put(dto.getDeviceNo(), dto.getSeqNo());

                    String prev = lastHash.get(dto.getDeviceNo());
                    if (prev == null) {
                        TempSample last = sampleRepo
                                .findTopByDeviceNoOrderBySeqNoDesc(dto.getDeviceNo()).orElse(null);
                        if (last != null) prev = last.getPayloadHash();
                    }
                    lastHash.put(dto.getDeviceNo(), prev);

                    Map<String, Object> payloadMap = new LinkedHashMap<>();
                    payloadMap.put("deviceNo", dto.getDeviceNo());
                    payloadMap.put("seqNo", dto.getSeqNo());
                    payloadMap.put("sampleAt", ClockUtil.formatIso(aligned));
                    payloadMap.put("temperature", dto.getTemperature());
                    payloadMap.put("humidity", dto.getHumidity());
                    payloadMap.put("doorOpen", dto.getDoorOpen() == null ? Boolean.FALSE : dto.getDoorOpen());
                    payloadMap.put("latitude", dto.getLatitude());
                    payloadMap.put("longitude", dto.getLongitude());
                    payloadMap.put("driverEvent", dto.getDriverEvent() == null ? "" : dto.getDriverEvent());
                    String payload = JsonUtil.toJson(payloadMap);

                    String hash = signatureUtil.chainHash(payload, prev);
                    String sig = signatureUtil.sign(hash);

                    TempSample s = new TempSample();
                    s.setDeviceNo(dto.getDeviceNo());
                    s.setTaskId(taskId);
                    s.setSampleAt(aligned);
                    s.setTemperature(dto.getTemperature());
                    s.setHumidity(dto.getHumidity());
                    s.setDoorOpen(dto.getDoorOpen() != null && dto.getDoorOpen());
                    s.setLatitude(dto.getLatitude());
                    s.setLongitude(dto.getLongitude());
                    s.setDriverEvent(dto.getDriverEvent());
                    s.setPayloadHash(hash);
                    s.setPrevHash(prev);
                    s.setSignature(sig);
                    s.setSeqNo(dto.getSeqNo());
                    sampleRepo.save(s);
                    resp.setSampleAccepted(resp.getSampleAccepted() + 1);
                } catch (Exception e) {
                    resp.setSampleRejected(resp.getSampleRejected() + 1);
                    log.error("【接入-采样】写入失败: device={} seq={} err={}",
                            dto.getDeviceNo(), dto.getSeqNo(), e.getMessage());
                }
            }
        }

        // 2) 轨迹点
        if (req.getTracks() != null) {
            for (TrackPointDto dto : req.getTracks()) {
                try {
                    TempRecorder r = recorderRepo.findByDeviceNo(dto.getDeviceNo()).orElse(null);
                    long skew = (r == null) ? 0L : r.getClockSkewMs();
                    Long taskId = null;
                    if (dto.getTaskNo() != null) {
                        taskId = taskRepo.findByTaskNo(dto.getTaskNo())
                                .map(TransportTask::getId).orElse(null);
                    }
                    OffsetDateTime aligned = ClockUtil.align(dto.getSampleAt(), skew);

                    List<TrackPoint> all = trackRepo.findAll();
                    TrackPoint last = null;
                    for (TrackPoint p : all) {
                        if (dto.getDeviceNo().equals(p.getDeviceNo())) {
                            if (last == null || p.getSeqNo() > last.getSeqNo()) last = p;
                        }
                    }
                    String prev = last == null ? null : last.getPayloadHash();

                    Map<String, Object> payloadMap = new LinkedHashMap<>();
                    payloadMap.put("deviceNo", dto.getDeviceNo());
                    payloadMap.put("seqNo", dto.getSeqNo());
                    payloadMap.put("sampleAt", ClockUtil.formatIso(aligned));
                    payloadMap.put("lat", dto.getLatitude());
                    payloadMap.put("lng", dto.getLongitude());
                    payloadMap.put("speed", dto.getSpeedKmh());
                    payloadMap.put("heading", dto.getHeading());
                    String payload = JsonUtil.toJson(payloadMap);

                    String hash = signatureUtil.chainHash(payload, prev);
                    String sig = signatureUtil.sign(hash);

                    TrackPoint p = new TrackPoint();
                    p.setDeviceNo(dto.getDeviceNo());
                    p.setTaskId(taskId);
                    p.setSampleAt(aligned);
                    p.setLatitude(dto.getLatitude());
                    p.setLongitude(dto.getLongitude());
                    p.setSpeedKmh(dto.getSpeedKmh());
                    p.setHeading(dto.getHeading());
                    p.setPayloadHash(hash);
                    p.setPrevHash(prev);
                    p.setSignature(sig);
                    p.setSeqNo(dto.getSeqNo());
                    trackRepo.save(p);
                    resp.setTrackAccepted(resp.getTrackAccepted() + 1);
                } catch (Exception e) {
                    resp.setTrackRejected(resp.getTrackRejected() + 1);
                    log.error("【接入-轨迹】写入失败: device={} seq={} err={}",
                            dto.getDeviceNo(), dto.getSeqNo(), e.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        resp.setElapsedMs(elapsed);
        resp.setMessage("ingest ok");
        Map<String, Object> oplogPayload = new LinkedHashMap<>();
        oplogPayload.put("sampleAccepted", resp.getSampleAccepted());
        oplogPayload.put("sampleRejected", resp.getSampleRejected());
        oplogPayload.put("trackAccepted", resp.getTrackAccepted());
        oplogPayload.put("trackRejected", resp.getTrackRejected());
        oplogPayload.put("elapsedMs", elapsed);
        opLogService.logAsync("SYSTEM", "SYSTEM", "INGEST", "SAMPLES",
                null, JsonUtil.toJson(oplogPayload), "OK", "127.0.0.1", "ingest-service");
        if (elapsed > 200) {
            log.warn("【接入-性能】耗时 {}ms samples={} tracks={}", elapsed,
                    resp.getSampleAccepted(), resp.getTrackAccepted());
        }
        return resp;
    }
}
