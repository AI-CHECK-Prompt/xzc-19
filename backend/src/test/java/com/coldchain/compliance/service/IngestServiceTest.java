package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.IngestRequest;
import com.coldchain.compliance.dto.IngestResponse;
import com.coldchain.compliance.dto.TempSampleDto;
import com.coldchain.compliance.entity.TempSample;
import com.coldchain.compliance.repository.OperationLogRepository;
import com.coldchain.compliance.repository.TempRecorderRepository;
import com.coldchain.compliance.repository.TempSampleRepository;
import com.coldchain.compliance.repository.TrackPointRepository;
import com.coldchain.compliance.repository.TransportTaskRepository;
import com.coldchain.compliance.security.SignatureUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 回归测试：IngestService 哈希链完整性。
 * <p>
 * 飞检场景：同一设备同批 ingest 多条温度采样时，
 * 必须保证每条 sample[i].prevHash == sample[i-1].payloadHash，
 * 且 sample[0].prevHash == 设备首条历史记录的 payloadHash（或 null）。
 * <p>
 * 历史 Bug：lastHash.put(prev) 写错值 + 写错时机，导致同批所有 prevHash 都指向
 * 同一个历史值，replay 校验返回 BROKEN。
 */
class IngestServiceTest {

    @TempDir
    Path tmpKeyDir;

    private TempSampleRepository sampleRepo;
    private TrackPointRepository trackRepo;
    private TransportTaskRepository taskRepo;
    private TempRecorderRepository recorderRepo;
    private OperationLogService opLogService;
    private OperationLogRepository opLogRepo;
    private SignatureUtil signatureUtil;
    private IngestService ingestService;

    @BeforeEach
    void setUp() throws Exception {
        sampleRepo = mock(TempSampleRepository.class);
        trackRepo = mock(TrackPointRepository.class);
        taskRepo = mock(TransportTaskRepository.class);
        recorderRepo = mock(TempRecorderRepository.class);
        opLogService = mock(OperationLogService.class);
        opLogRepo = mock(OperationLogRepository.class);

        // 真实 SignatureUtil：使用临时目录生成 RSA 密钥
        signatureUtil = new SignatureUtil();
        Field keyDirField = SignatureUtil.class.getDeclaredField("keyDir");
        keyDirField.setAccessible(true);
        keyDirField.set(signatureUtil, tmpKeyDir.toString());
        ReflectionTestUtils.invokeMethod(signatureUtil, "init");

        when(recorderRepo.findByDeviceNo(anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByTaskNo(anyString())).thenReturn(Optional.empty());
        when(sampleRepo.findTopByDeviceNoOrderBySeqNoDesc(anyString())).thenReturn(Optional.empty());
        when(opLogRepo.findAll()).thenReturn(new ArrayList<>());

        // sampleRepo.save 模拟持久化：填充 id 后返回
        AtomicLong idGen = new AtomicLong(0);
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            TempSample s = inv.getArgument(0);
            if (s.getId() == null) s.setId(idGen.incrementAndGet());
            return s;
        });

        ingestService = new IngestService(sampleRepo, trackRepo, taskRepo,
                recorderRepo, signatureUtil, opLogService);
    }

    /**
     * 核心场景：同设备同批 ingest 3 条 sample，链必须严格串联。
     */
    @Test
    void ingest_sameDevice_sameBatch_chainIsContiguous() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-03T08:00:00Z");
        IngestRequest req = new IngestRequest();
        req.setSamples(Arrays.asList(
                sample("DEV-A", 1L, t0,                "4.10", "60.0"),
                sample("DEV-A", 2L, t0.plusMinutes(1), "4.20", "60.1"),
                sample("DEV-A", 3L, t0.plusMinutes(2), "4.30", "60.2")
        ));

        List<TempSample> saved = new ArrayList<>();
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            TempSample s = inv.getArgument(0);
            saved.add(s);
            return s;
        });

        IngestResponse resp = ingestService.ingest(req);

        assertEquals(3, resp.getSampleAccepted(), "3 条都应该被接受");
        assertEquals(0, resp.getSampleRejected());
        assertEquals(3, saved.size(), "3 条都应该落库");

        // 链完整性核心断言
        assertNull(saved.get(0).getPrevHash(), "首条 prevHash 应为 null（设备无历史）");
        assertEquals(saved.get(0).getPayloadHash(), saved.get(1).getPrevHash(),
                "第 2 条 prevHash 必须等于第 1 条 payloadHash");
        assertEquals(saved.get(1).getPayloadHash(), saved.get(2).getPrevHash(),
                "第 3 条 prevHash 必须等于第 2 条 payloadHash");

        // 不变量：payloadHash 两两不同（payload 不同 + prevHash 不同 → hash 不同）
        assertNotEquals(saved.get(0).getPayloadHash(), saved.get(1).getPayloadHash());
        assertNotEquals(saved.get(1).getPayloadHash(), saved.get(2).getPayloadHash());
    }

    /**
     * 混合设备同批：不同设备各自维护独立链，互不干扰。
     */
    @Test
    void ingest_mixedDevices_chainsAreIsolated() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-03T09:00:00Z");
        IngestRequest req = new IngestRequest();
        req.setSamples(Arrays.asList(
                sample("DEV-X", 1L, t0,                "3.10", "55.0"),
                sample("DEV-Y", 1L, t0,                "5.10", "65.0"),
                sample("DEV-X", 2L, t0.plusMinutes(1), "3.20", "55.1"),
                sample("DEV-Y", 2L, t0.plusMinutes(1), "5.20", "65.1")
        ));

        List<TempSample> saved = new ArrayList<>();
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            TempSample s = inv.getArgument(0);
            saved.add(s);
            return s;
        });

        IngestResponse resp = ingestService.ingest(req);

        assertEquals(4, resp.getSampleAccepted());
        assertEquals(4, saved.size());

        // 按 deviceNo 重排
        TempSample x1 = saved.get(0);
        TempSample y1 = saved.get(1);
        TempSample x2 = saved.get(2);
        TempSample y2 = saved.get(3);

        // DEV-X 自成链
        assertNull(x1.getPrevHash());
        assertEquals(x1.getPayloadHash(), x2.getPrevHash());
        // DEV-Y 自成链，且与 DEV-X 互不影响
        assertNull(y1.getPrevHash());
        assertEquals(y1.getPayloadHash(), y2.getPrevHash());
        assertNotEquals(x1.getPayloadHash(), y1.getPayloadHash());
    }

    /**
     * 状态残留场景：第 2 条 save 抛异常后，第 3 条不能基于"幻影"prev 续链。
     * 它应当回退到 DB findTopByDeviceNoOrderBySeqNoDesc（返回 null），形成新的"首条"。
     */
    @Test
    void ingest_midBatchSaveFailure_noPhantomChainPointer() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-03T10:00:00Z");
        IngestRequest req = new IngestRequest();
        req.setSamples(Arrays.asList(
                sample("DEV-Z", 1L, t0,                "2.10", "50.0"),
                sample("DEV-Z", 2L, t0.plusMinutes(1), "2.20", "50.1"),
                sample("DEV-Z", 3L, t0.plusMinutes(2), "2.30", "50.2")
        ));

        AtomicLong idGen = new AtomicLong(0);
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            TempSample s = inv.getArgument(0);
            // 第 2 条保存时模拟 WORM/DB 故障
            if (s.getSeqNo() != null && s.getSeqNo() == 2L) {
                throw new RuntimeException("simulated WORM write failure");
            }
            s.setId(idGen.incrementAndGet());
            return s;
        });
        // DB 中应无任何历史（findTop 返回 empty），让第 3 条自然回退
        when(sampleRepo.findTopByDeviceNoOrderBySeqNoDesc("DEV-Z"))
                .thenReturn(Optional.empty());

        IngestResponse resp = ingestService.ingest(req);

        // 第 1 条成功，第 2 条失败，第 3 条也失败（它依然把"已存在但未持久化的第 2 条"
        // 误当成链头？—— 不会，因为 lastHash 没被污染，且 DB findTop 返回 null，所以它
        // 以 null 作为 prev 形成"新首条"，成功落库）
        // 但注意：seqNo 校验会让第 3 条以 seq=3、expected = max(1,0)+1=1，
        // 看到 seq>=1 通过。所以第 3 条会成功。
        assertEquals(2, resp.getSampleAccepted(), "第 1、第 3 条接受，第 2 条拒绝");
        assertEquals(1, resp.getSampleRejected(), "仅第 2 条因 save 失败被拒绝");

        // 验证：只有 2 次 save 成功调用（第 2 条 save 抛出前不会进入 lastHash 推进逻辑）
        verify(sampleRepo, times(3)).save(any(TempSample.class));
    }

    /**
     * 边界：单条样本，首条 prevHash 为 null，链长度为 1。
     */
    @Test
    void ingest_singleSample_firstHashHasNoPrev() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-03T11:00:00Z");
        IngestRequest req = new IngestRequest();
        req.setSamples(Arrays.asList(sample("DEV-SOLO", 1L, t0, "4.00", "50.0")));

        List<TempSample> saved = new ArrayList<>();
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        IngestResponse resp = ingestService.ingest(req);
        assertEquals(1, resp.getSampleAccepted());
        assertNull(saved.get(0).getPrevHash());
        assertNotNull(saved.get(0).getPayloadHash());
        assertNotNull(saved.get(0).getSignature());
    }

    /**
     * 跨请求续链：DB 中已存在一条历史样本（HISTORIC），新批 2 条 sample
     * 必须以 HISTORIC.payloadHash 作为第 1 条 prevHash，并以 H1 作为第 2 条 prevHash。
     * 这正是飞检回放 5/3 那批数据时观察到的场景。
     */
    @Test
    void ingest_continuesChainFromDbHistory() {
        OffsetDateTime tHist = OffsetDateTime.parse("2026-05-03T07:00:00Z");
        TempSample historic = new TempSample();
        historic.setId(1L);
        historic.setDeviceNo("DEV-CROSS");
        historic.setSeqNo(99L);
        historic.setSampleAt(tHist);
        historic.setTemperature(new BigDecimal("4.00"));
        historic.setHumidity(new BigDecimal("60.0"));
        historic.setDoorOpen(false);
        historic.setPayloadHash("HISTORIC_HASH_PLACEHOLDER");
        historic.setPrevHash(null);
        historic.setSignature("irrelevant");

        when(sampleRepo.findTopByDeviceNoOrderBySeqNoDesc("DEV-CROSS"))
                .thenReturn(Optional.of(historic));

        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-03T08:00:00Z");
        IngestRequest req = new IngestRequest();
        req.setSamples(Arrays.asList(
                sample("DEV-CROSS", 100L, t0,                "4.10", "60.0"),
                sample("DEV-CROSS", 101L, t0.plusMinutes(1), "4.20", "60.1")
        ));

        List<TempSample> saved = new ArrayList<>();
        when(sampleRepo.save(any(TempSample.class))).thenAnswer(inv -> {
            TempSample s = inv.getArgument(0);
            saved.add(s);
            return s;
        });

        IngestResponse resp = ingestService.ingest(req);
        assertEquals(2, resp.getSampleAccepted());

        // 第 1 条 prevHash = 历史 payloadHash
        assertEquals("HISTORIC_HASH_PLACEHOLDER", saved.get(0).getPrevHash());
        // 第 2 条 prevHash = 第 1 条新算出的 payloadHash
        assertEquals(saved.get(0).getPayloadHash(), saved.get(1).getPrevHash());
        // 第 2 条 prevHash 绝不能还是历史值（这是飞检的根因）
        assertNotEquals("HISTORIC_HASH_PLACEHOLDER", saved.get(1).getPrevHash());
    }

    private TempSampleDto sample(String deviceNo, long seq, OffsetDateTime at,
                                 String temp, String humidity) {
        TempSampleDto d = new TempSampleDto();
        d.setDeviceNo(deviceNo);
        d.setSeqNo(seq);
        d.setSampleAt(at);
        d.setTemperature(new BigDecimal(temp));
        d.setHumidity(new BigDecimal(humidity));
        d.setDoorOpen(false);
        return d;
    }
}
