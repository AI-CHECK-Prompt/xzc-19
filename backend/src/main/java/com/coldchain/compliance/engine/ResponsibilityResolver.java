package com.coldchain.compliance.engine;

import com.coldchain.compliance.entity.AuditFinding;
import com.coldchain.compliance.entity.TransportSegment;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 责任段判定器：给定一个 finding 和一段候选 segment 列表，
 * 判定该 finding 应归属到哪一段（哪段对最终结论负责）。
 * <p>
 * 判定流程：
 * 1. 排除非在途段（is_in_transit=false）
 * 2. finding 时间区间落入段的时间窗口 → 命中
 * 3. 时间窗口不重叠则按 seq 找最近的段
 * 4. 默认返回承运商责任方
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponsibilityResolver {

    public static class ResponsibilityResult {
        public TransportSegment responsibleSegment;
        public String responsibleParty;     // CARRIER / ENTERPRISE / SUPPLIER
        public String segmentNo;
        public Long carrierId;
        public String carrierName;
        public boolean isInTransit;          // 是否在途段
        public String reason;
    }

    /**
     * @param finding  审计证据（包含 timeRangeStart/End）
     * @param segments  候选段列表（按 seq 升序）
     */
    public ResponsibilityResult resolve(AuditFinding finding, List<TransportSegment> segments) {
        ResponsibilityResult r = new ResponsibilityResult();
        if (segments == null || segments.isEmpty()) {
            r.responsibleParty = Constants.PARTY_ENTERPRISE;
            r.reason = "无候选段，默认企业责任";
            return r;
        }

        // 仅在途段参与判定
        List<TransportSegment> inTransit = new ArrayList<>();
        for (TransportSegment s : segments) {
            if (Boolean.TRUE.equals(s.getIsInTransit())) inTransit.add(s);
        }
        if (inTransit.isEmpty()) {
            r.responsibleParty = Constants.PARTY_ENTERPRISE;
            r.reason = "全部为非在途段（堆场/中转），无承运商可追责";
            return r;
        }

        OffsetDateTime start = finding.getTimeRangeStart();
        OffsetDateTime end = finding.getTimeRangeEnd();

        // 1) 时间窗口匹配
        for (TransportSegment s : inTransit) {
            OffsetDateTime ds = s.getActualDepartAt() == null ? s.getPlannedDepartAt() : s.getActualDepartAt();
            OffsetDateTime as = s.getActualArriveAt() == null ? s.getPlannedArriveAt() : s.getActualArriveAt();
            if (ds == null || as == null) continue;
            if (start != null && end != null && !start.isBefore(ds) && !end.isAfter(as)) {
                fillSegment(r, s, "时间窗口命中");
                return r;
            }
        }
        // 2) 中点匹配
        if (start != null && end != null) {
            long midSec = Duration.between(start, end).getSeconds() / 2;
            OffsetDateTime mid = start.plusSeconds(midSec);
            for (TransportSegment s : inTransit) {
                OffsetDateTime ds = s.getActualDepartAt() == null ? s.getPlannedDepartAt() : s.getActualDepartAt();
                OffsetDateTime as = s.getActualArriveAt() == null ? s.getPlannedArriveAt() : s.getActualArriveAt();
                if (ds == null || as == null) continue;
                if (!mid.isBefore(ds) && !mid.isAfter(as)) {
                    fillSegment(r, s, "时间中点命中");
                    return r;
                }
            }
        }
        // 3) 最近段
        TransportSegment best = inTransit.get(0);
        long bestDelta = Long.MAX_VALUE;
        OffsetDateTime ref;
        if (start != null && end != null) {
            long refSec = Duration.between(start, end).getSeconds() / 2;
            ref = start.plus(refSec, ChronoUnit.SECONDS);
        } else {
            ref = (start != null ? start : OffsetDateTime.now());
        }
        for (TransportSegment s : inTransit) {
            OffsetDateTime ds = s.getActualDepartAt() == null ? s.getPlannedDepartAt() : s.getActualDepartAt();
            if (ds == null) continue;
            long d = Math.abs(Duration.between(ref, ds).getSeconds());
            if (d < bestDelta) { bestDelta = d; best = s; }
        }
        fillSegment(r, best, "时间窗口无精确匹配，回退到最近段");
        return r;
    }

    private void fillSegment(ResponsibilityResult r, TransportSegment s, String reason) {
        r.responsibleSegment = s;
        r.responsibleParty = Constants.PARTY_CARRIER;
        r.segmentNo = s.getSegmentNo();
        r.carrierId = s.getCarrierId();
        r.carrierName = null;
        r.isInTransit = Boolean.TRUE.equals(s.getIsInTransit());
        r.reason = reason;
        log.info("【责任段】finding={} → 段 {} 责任方={} 原因={}",
                s.getSegmentNo(), s.getSegmentNo(), r.responsibleParty, reason);
    }
}
