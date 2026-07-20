package com.coldchain.compliance.util;

import lombok.extern.slf4j.Slf4j;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时钟对齐工具。
 * <p>
 * 不同车载记录仪存在时钟漂移；通过 temp_recorder.clock_skew_ms 校准采样时间，
 * 保证温度曲线与轨迹在统一时间轴上对齐。
 */
@Slf4j
public final class ClockUtil {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private ClockUtil() {}

    /** 校准设备时钟：sample_at + skew_ms（毫秒） */
    public static OffsetDateTime align(OffsetDateTime sampleAt, long skewMs) {
        if (sampleAt == null) return null;
        return sampleAt.plusNanos(skewMs * 1_000_000L);
    }

    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static String formatIso(OffsetDateTime t) {
        return t == null ? null : t.format(ISO);
    }
}
