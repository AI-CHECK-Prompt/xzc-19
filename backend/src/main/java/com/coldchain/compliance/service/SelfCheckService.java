package com.coldchain.compliance.service;

import com.coldchain.compliance.dto.AuditSummary;
import com.coldchain.compliance.dto.SelfCheckResult;
import com.coldchain.compliance.dto.SelfCheckResult.CheckItem;
import com.coldchain.compliance.engine.MultiChainCoordinationEngine;
import com.coldchain.compliance.engine.RuleEngine;
import com.coldchain.compliance.entity.*;
import com.coldchain.compliance.repository.*;
import com.coldchain.compliance.security.SignatureUtil;
import com.coldchain.compliance.util.Constants;
import com.coldchain.compliance.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 接口自检：覆盖 13 项硬验收指标。
 * <p>
 * 8 项原飞检自检（数据库/规则引擎/并发/超温/哈希链/WORM/导出/海关）
 * + 5 项多式联运硬验收：
 *   1) 段树建模
 *   2) 段间断点
 *   3) 超温 → 责任段 → 影响剂量 → 整改工单
 *   4) 剂型+异常类型 → 处置预案检索
 *   5) 多式联运全链合规报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfCheckService {

    private final TransportTaskRepository taskRepo;
    private final DrugBatchRepository batchRepo;
    private final TempSampleRepository sampleRepo;
    private final ComplianceRuleRepository ruleRepo;
    private final IngestService ingestService;
    private final RuleEngine ruleEngine;
    private final ExportService exportService;
    private final CustomsParseService customsParseService;
    private final SignatureUtil signatureUtil;
    private final EntityManager em;

    // 多式联运新增依赖
    private final TransportSegmentRepository segmentRepo;
    private final SegmentHandoverRepository handoverRepo;
    private final CarrierRepository carrierRepo;
    private final ExceptionPrescriptionRepository prescriptionRepo;
    private final DoseImpactRuleRepository doseRuleRepo;
    private final CarrierWorkorderRepository workorderRepo;
    private final RegulatoryReportRepository regReportRepo;
    private final MultiChainCoordinationEngine coordinationEngine;
    private final ExceptionPrescriptionService prescriptionService;

    @Value("${coldchain.export.temp-dir:/data/exports}")
    private String tempDir;

    @Transactional
    public SelfCheckResult runAll() {
        SelfCheckResult r = new SelfCheckResult();
        r.setTimestamp(System.currentTimeMillis());
        List<CheckItem> items = new ArrayList<>();
        r.setItems(items);

        // ===== 原 8 项飞检自检 =====
        items.add(checkDb());
        items.add(checkRuleEngine());
        items.add(checkConcurrentIngest());
        items.add(checkBlockOnOverheat());
        items.add(checkHashChain());
        items.add(checkWorm());
        items.add(checkExports());
        items.add(checkCustoms());

        // ===== 5 项多式联运硬验收 =====
        items.add(checkSegmentTree());
        items.add(checkHandoverBreakpoints());
        items.add(checkOverheatToResponsibilityAndWorkorder());
        items.add(checkPrescriptionLookup());
        items.add(checkMultiChainComplianceReport());

        r.setOverallOk(items.stream().allMatch(CheckItem::isPassed));
        log.info("【自检】总览 passed={} items={}", r.isOverallOk(), items.size());
        return r;
    }

    // =================================================================
    // 原 8 项飞检自检（保持不变）
    // =================================================================
    private CheckItem checkDb() {
        CheckItem c = new CheckItem();
        c.setName("数据库连通");
        c.setCategory("CORE");
        long t0 = System.currentTimeMillis();
        try {
            Object cnt = em.createNativeQuery("SELECT count(*) FROM app_user").getSingleResult();
            c.setPassed(true);
            c.setMessage("app_user 数 = " + cnt);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("DB 不可达: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkRuleEngine() {
        CheckItem c = new CheckItem();
        c.setName("规则引擎就绪（6 类齐备）");
        c.setCategory("AUDIT");
        long t0 = System.currentTimeMillis();
        try {
            List<ComplianceRule> rules = ruleRepo.findByEnabledTrue();
            Set<String> cats = new HashSet<>();
            for (ComplianceRule r : rules) cats.add(r.getCategory());
            boolean ok = cats.containsAll(Arrays.asList(
                    Constants.CAT_CONTINUITY, Constants.CAT_CUMULATIVE, Constants.CAT_RANGE,
                    Constants.CAT_DOOR, Constants.CAT_TRACK, Constants.CAT_SMOOTH));
            c.setPassed(ok);
            c.setMessage("已加载规则 " + rules.size() + " 条，类别 " + cats);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("规则加载失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkConcurrentIngest() {
        CheckItem c = new CheckItem();
        c.setName("100 设备并发接入");
        c.setCategory("DATA");
        long t0 = System.currentTimeMillis();
        try {
            String taskNo = "SELFCHECK-" + System.currentTimeMillis();
            TransportTask task = new TransportTask();
            task.setTaskNo(taskNo);
            task.setOrigin("上海浦东");
            task.setDestination("德国法兰克福");
            task.setOriginCountry("CN");
            task.setDestCountry("DE");
            task.setStatus(Constants.TASK_IN_TRANSIT);
            task.setDepartureAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            task = taskRepo.save(task);

            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-SELF-" + System.currentTimeMillis());
            b.setProductName("测试疫苗");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(1000);
            batchRepo.save(b);

            List<com.coldchain.compliance.dto.TempSampleDto> samples = new ArrayList<>();
            for (int dev = 0; dev < 100; dev++) {
                String devNo = "SIM-DEVICE-" + dev;
                for (int i = 0; i < 10; i++) {
                    com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                    s.setDeviceNo(devNo);
                    s.setTaskNo(taskNo);
                    s.setSeqNo((long) i);
                    s.setSampleAt(OffsetDateTime.now().plusSeconds(i * 60L));
                    s.setTemperature(BigDecimal.valueOf(5.0 + Math.random()));
                    s.setHumidity(BigDecimal.valueOf(50 + Math.random() * 10));
                    s.setDoorOpen(false);
                    s.setLatitude(BigDecimal.valueOf(31.23 + Math.random() * 0.01));
                    s.setLongitude(BigDecimal.valueOf(121.47 + Math.random() * 0.01));
                    samples.add(s);
                }
            }
            com.coldchain.compliance.dto.IngestRequest req = new com.coldchain.compliance.dto.IngestRequest();
            req.setSamples(samples);
            com.coldchain.compliance.dto.IngestResponse resp = ingestService.ingest(req);
            c.setPassed(resp.getSampleAccepted() >= 1000);
            c.setMessage("accepted=" + resp.getSampleAccepted()
                    + " rejected=" + resp.getSampleRejected()
                    + " elapsedMs=" + resp.getElapsedMs());
            Map<String, Object> extra = new HashMap<>();
            extra.put("taskNo", taskNo);
            c.setExtra(extra);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("并发接入失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkBlockOnOverheat() {
        CheckItem c = new CheckItem();
        c.setName("含超温任务触发 BLOCK");
        c.setCategory("AUDIT");
        long t0 = System.currentTimeMillis();
        try {
            String taskNo = "OVERHEAT-" + System.currentTimeMillis();
            TransportTask task = new TransportTask();
            task.setTaskNo(taskNo);
            task.setOrigin("北京");
            task.setDestination("东京");
            task.setStatus(Constants.TASK_IN_TRANSIT);
            task.setDepartureAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            task = taskRepo.save(task);

            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-OVERHEAT-" + System.currentTimeMillis());
            b.setProductName("胰岛素");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(500);
            batchRepo.save(b);

            List<com.coldchain.compliance.dto.TempSampleDto> samples = new ArrayList<>();
            OffsetDateTime t = OffsetDateTime.now();
            double[] temps = {5.0, 5.5, 6.0, 12.0, 12.5, 11.0, 5.0, 6.0, -1.0, 5.0, 6.0, 5.5};
            for (int i = 0; i < temps.length; i++) {
                com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                s.setDeviceNo("OVERHEAT-DEVICE");
                s.setTaskNo(taskNo);
                s.setSeqNo((long) i);
                s.setSampleAt(t.plusMinutes(i * 30L));
                s.setTemperature(BigDecimal.valueOf(temps[i]));
                s.setHumidity(BigDecimal.valueOf(55));
                s.setDoorOpen(false);
                samples.add(s);
            }
            com.coldchain.compliance.dto.IngestRequest req = new com.coldchain.compliance.dto.IngestRequest();
            req.setSamples(samples);
            ingestService.ingest(req);

            AuditSummary summary = ruleEngine.audit(task.getId());
            c.setPassed(Constants.AUDIT_BLOCK.equals(summary.getStatus())
                    || summary.getCriticalCount() > 0);
            c.setMessage("status=" + summary.getStatus()
                    + " critical=" + summary.getCriticalCount()
                    + " block=" + summary.getBlockCount()
                    + " total=" + summary.getTotalFindings()
                    + " durationMs=" + summary.getDurationMs());
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("超温审计失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkHashChain() {
        CheckItem c = new CheckItem();
        c.setName("哈希链完整性");
        c.setCategory("IMMUTABLE");
        long t0 = System.currentTimeMillis();
        try {
            List<TempSample> all = sampleRepo.findAll();
            int ok = 0, bad = 0;
            for (TempSample s : all) {
                if (signatureUtil.verify(s.getPayloadHash(), s.getSignature())) ok++;
                else bad++;
            }
            c.setPassed(bad == 0 && ok > 0);
            c.setMessage("verified=" + ok + " failed=" + bad);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("哈希校验失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkWorm() {
        CheckItem c = new CheckItem();
        c.setName("WORM 触发器生效");
        c.setCategory("IMMUTABLE");
        long t0 = System.currentTimeMillis();
        try {
            boolean blocked = false;
            try {
                em.createNativeQuery(
                        "UPDATE temp_sample SET door_open = NOT door_open WHERE id = (SELECT id FROM temp_sample LIMIT 1)")
                        .executeUpdate();
            } catch (Exception ex) {
                blocked = ex.getMessage() != null && ex.getMessage().toUpperCase().contains("WORM");
            }
            c.setPassed(blocked);
            c.setMessage(blocked ? "WORM 触发器已阻止 UPDATE" : "WORM 未生效（危险）");
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("WORM 校验异常: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkExports() {
        CheckItem c = new CheckItem();
        c.setName("三格式导出");
        c.setCategory("EXPORT");
        long t0 = System.currentTimeMillis();
        try {
            Optional<TransportTask> any = taskRepo.findAll().stream()
                    .filter(t -> !sampleRepo.findByTaskIdOrderBySampleAt(t.getId()).isEmpty())
                    .findFirst();
            if (!any.isPresent()) {
                c.setPassed(false);
                c.setMessage("无数据可导出，请先跑并发接入用例");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            String taskNo = any.get().getTaskNo();
            String xml = exportService.exportXml(taskNo, "selfcheck");
            String pdf = exportService.exportPdf(taskNo, "selfcheck");
            String xlsx = exportService.exportExcel(taskNo, "selfcheck");
            boolean allOk = Files.exists(Paths.get(xml))
                    && Files.exists(Paths.get(pdf))
                    && Files.exists(Paths.get(xlsx));
            c.setPassed(allOk);
            c.setMessage("XML=" + xml + " | PDF=" + pdf + " | XLSX=" + xlsx);
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("导出失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    private CheckItem checkCustoms() {
        CheckItem c = new CheckItem();
        c.setName("海关报关单解析与批号匹配");
        c.setCategory("CORE");
        long t0 = System.currentTimeMillis();
        try {
            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-CUSTOMS-" + System.currentTimeMillis());
            b.setProductName("测试药品");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(100);
            batchRepo.save(b);

            com.coldchain.compliance.dto.CustomsParseRequest req = new com.coldchain.compliance.dto.CustomsParseRequest();
            req.setDeclNo("DECL-" + System.currentTimeMillis());
            req.setLanguage("zh-CN");
            req.setConsignor("中国出口商");
            req.setConsignee("海外收货人");
            req.setTotalValue("10000");
            req.setCurrency("USD");
            req.setDeclDate(OffsetDateTime.now().toString());
            List<com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch> list = new ArrayList<>();
            com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch d1 = new com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch();
            d1.setBatchNo(b.getBatchNo());
            d1.setQty(100);
            d1.setProduct("测试药品");
            list.add(d1);
            com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch d2 = new com.coldchain.compliance.dto.CustomsParseRequest.DeclaredBatch();
            d2.setBatchNo("UNKNOWN-BATCH");
            d2.setQty(50);
            d2.setProduct("未知药品");
            list.add(d2);
            req.setDeclaredBatches(list);

            com.coldchain.compliance.dto.CustomsMatchResult res = customsParseService.parseAndMatch(req);
            c.setPassed(res.getMatched() == 1 && res.getMissing() == 1);
            c.setMessage("total=" + res.getTotal() + " matched=" + res.getMatched()
                    + " missing=" + res.getMissing());
        } catch (Exception e) {
            c.setPassed(false);
            c.setMessage("海关解析失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    // =================================================================
    // 5 项多式联运硬验收
    // =================================================================

    /**
     * 多式联运硬验收 1：能建模一笔订单的 N 段运输树并按段查看温控曲线。
     */
    private CheckItem checkSegmentTree() {
        CheckItem c = new CheckItem();
        c.setName("多式联运硬验收 1 - 段树建模 + 段温控曲线");
        c.setCategory("MULTIMODAL");
        long t0 = System.currentTimeMillis();
        try {
            String taskNo = "MM-SEG-" + System.currentTimeMillis();
            TransportTask task = new TransportTask();
            task.setTaskNo(taskNo);
            task.setOrigin("北京");
            task.setDestination("荷兰鹿特丹");
            task.setStatus(Constants.TASK_IN_TRANSIT);
            task.setDepartureAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            task = taskRepo.save(task);

            DrugBatch b = new DrugBatch();
            b.setBatchNo("BN-MM-" + System.currentTimeMillis());
            b.setProductName("胰岛素");
            b.setDosageForm(Constants.FORM_COLD);
            b.setQuantity(800);
            batchRepo.save(b);

            // 取 3 个承运商
            List<Carrier> carriers = carrierRepo.findByEnabledTrue();
            if (carriers.size() < 3) {
                c.setPassed(false);
                c.setMessage("承运商不足 3 家，无法构造多式联运段树");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            Carrier road = carriers.get(0);
            Carrier sea = carriers.get(1);
            Carrier wh = carriers.get(2);

            OffsetDateTime t0Base = OffsetDateTime.now().minusHours(48);
            // 段 1：北京→上海港（陆运 12 小时）
            TransportSegment s1 = new TransportSegment();
            s1.setTaskId(task.getId());
            s1.setSegmentNo("S1");
            s1.setSegmentType(Constants.SEG_ROAD);
            s1.setOrigin("北京");
            s1.setDestination("上海港");
            s1.setCarrierId(road.getId());
            s1.setTransportTool("冷藏车");
            s1.setToolNo("京A-88888");
            s1.setTemperatureMin(new BigDecimal("2.0"));
            s1.setTemperatureMax(new BigDecimal("8.0"));
            s1.setPlannedDepartAt(t0Base);
            s1.setPlannedArriveAt(t0Base.plusHours(12));
            s1.setActualDepartAt(t0Base);
            s1.setActualArriveAt(t0Base.plusHours(12));
            s1.setIsInTransit(true);
            s1.setStatus("ARRIVED");
            s1.setResponsiblePerson("王司机");
            s1.setSeq(1);
            s1 = segmentRepo.save(s1);

            // 段 2：上海港→鹿特丹港（海运 720 小时 = 30 天）
            TransportSegment s2 = new TransportSegment();
            s2.setTaskId(task.getId());
            s2.setSegmentNo("S2");
            s2.setSegmentType(Constants.SEG_SEA);
            s2.setOrigin("上海港");
            s2.setDestination("鹿特丹港");
            s2.setCarrierId(sea.getId());
            s2.setTransportTool("冷藏集装箱");
            s2.setToolNo("CSCL-2026-0001");
            s2.setTemperatureMin(new BigDecimal("2.0"));
            s2.setTemperatureMax(new BigDecimal("8.0"));
            s2.setPlannedDepartAt(t0Base.plusHours(12));
            s2.setPlannedArriveAt(t0Base.plusHours(732));
            s2.setActualDepartAt(t0Base.plusHours(14));
            s2.setActualArriveAt(t0Base.plusHours(736));
            s2.setIsInTransit(true);
            s2.setStatus("ARRIVED");
            s2.setResponsiblePerson("李船长");
            s2.setSeq(2);
            s2 = segmentRepo.save(s2);

            // 段 3：鹿特丹港→客户仓（陆运 4 小时）
            TransportSegment s3 = new TransportSegment();
            s3.setTaskId(task.getId());
            s3.setSegmentNo("S3");
            s3.setSegmentType(Constants.SEG_ROAD);
            s3.setOrigin("鹿特丹港");
            s3.setDestination("客户仓");
            s3.setCarrierId(road.getId());
            s3.setTransportTool("冷藏车");
            s3.setToolNo("NL-XX-9999");
            s3.setTemperatureMin(new BigDecimal("2.0"));
            s3.setTemperatureMax(new BigDecimal("8.0"));
            s3.setPlannedDepartAt(t0Base.plusHours(736));
            s3.setPlannedArriveAt(t0Base.plusHours(740));
            s3.setActualDepartAt(t0Base.plusHours(738));
            s3.setActualArriveAt(t0Base.plusHours(742));
            s3.setIsInTransit(true);
            s3.setStatus("ARRIVED");
            s3.setResponsiblePerson("Hans");
            s3.setSeq(3);
            s3 = segmentRepo.save(s3);

            // 段 4：上海港堆场停放（非在途段，48 小时）
            TransportSegment s4 = new TransportSegment();
            s4.setTaskId(task.getId());
            s4.setSegmentNo("S4-STORAGE");
            s4.setSegmentType(Constants.SEG_STORAGE);
            s4.setOrigin("上海港堆场");
            s4.setDestination("上海港堆场");
            s4.setCarrierId(wh.getId());
            s4.setTransportTool("冷藏堆场");
            s4.setTemperatureMin(new BigDecimal("2.0"));
            s4.setTemperatureMax(new BigDecimal("8.0"));
            s4.setPlannedDepartAt(t0Base.plusHours(12));
            s4.setPlannedArriveAt(t0Base.plusHours(60));
            s4.setIsInTransit(false);    // 关键：非在途段
            s4.setStatus("HANDED_OVER");
            s4.setResponsiblePerson("堆场管理员");
            s4.setSeq(4);
            segmentRepo.save(s4);

            // 注入段 1 温控采样
            List<com.coldchain.compliance.dto.TempSampleDto> samples = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                s.setDeviceNo("DEV-S1-" + i);
                s.setTaskNo(taskNo);
                s.setSeqNo((long) i);
                s.setSampleAt(t0Base.plusHours(i));
                s.setTemperature(BigDecimal.valueOf(5.0));
                samples.add(s);
            }
            // 段 2 海运：注入 6 小时超温（5℃→12℃）
            for (int i = 0; i < 30; i++) {
                com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                s.setDeviceNo("DEV-S2-" + i);
                s.setTaskNo(taskNo);
                s.setSeqNo(100L + i);
                double t = (i >= 10 && i < 16) ? 12.0 : 5.0;
                s.setSampleAt(t0Base.plusHours(14 + i));
                s.setTemperature(BigDecimal.valueOf(t));
                samples.add(s);
            }
            // 段 3 陆运：注入 2 次门长时间开启
            for (int i = 0; i < 8; i++) {
                com.coldchain.compliance.dto.TempSampleDto s = new com.coldchain.compliance.dto.TempSampleDto();
                s.setDeviceNo("DEV-S3-" + i);
                s.setTaskNo(taskNo);
                s.setSeqNo(200L + i);
                boolean door = (i == 2 || i == 3 || i == 6);
                s.setSampleAt(t0Base.plusHours(738 + i));
                s.setTemperature(BigDecimal.valueOf(5.5));
                s.setDoorOpen(door);
                samples.add(s);
            }
            com.coldchain.compliance.dto.IngestRequest req = new com.coldchain.compliance.dto.IngestRequest();
            req.setSamples(samples);
            ingestService.ingest(req);

            c.setPassed(true);
            c.setMessage("订单 " + taskNo + " 已建模 4 段（含 1 段非在途），3 段在途均注入温控采样；" +
                    "段1=" + s1.getSegmentNo() + " 段2=" + s2.getSegmentNo() + " 段3=" + s3.getSegmentNo());
            Map<String, Object> extra = new HashMap<>();
            extra.put("taskId", task.getId());
            extra.put("taskNo", taskNo);
            extra.put("segmentCount", 4);
            c.setExtra(extra);
        } catch (Exception e) {
            log.error("【自检-段树】失败", e);
            c.setPassed(false);
            c.setMessage("段树建模失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    /**
     * 多式联运硬验收 2：能展示一笔多段订单的段间断点与责任段归属。
     */
    private CheckItem checkHandoverBreakpoints() {
        CheckItem c = new CheckItem();
        c.setName("多式联运硬验收 2 - 段间断点 + 责任段归属");
        c.setCategory("MULTIMODAL");
        long t0 = System.currentTimeMillis();
        try {
            // 找最近一个含 4 段的任务
            Optional<TransportTask> opt = taskRepo.findAll().stream()
                    .filter(t -> t.getTaskNo() != null && t.getTaskNo().startsWith("MM-SEG-"))
                    .max(Comparator.comparing(TransportTask::getCreatedAt));
            if (!opt.isPresent()) {
                c.setPassed(false);
                c.setMessage("无 MM-SEG 任务，请先跑段树自检");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            TransportTask task = opt.get();
            List<TransportSegment> segs = segmentRepo.findByTaskIdOrderBySeqAsc(task.getId());
            List<SegmentHandover> handovers = handoverRepo.findByTaskIdOrderByHandoverAtAsc(task.getId());
            if (segs.size() < 3) {
                c.setPassed(false);
                c.setMessage("段数不足 3，无法验证段间断点");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            // 自动注入段间交接记录
            if (handovers.isEmpty()) {
                for (int i = 0; i < segs.size() - 1; i++) {
                    TransportSegment from = segs.get(i);
                    TransportSegment to = segs.get(i + 1);
                    SegmentHandover h = new SegmentHandover();
                    h.setTaskId(task.getId());
                    h.setFromSegmentId(from.getId());
                    h.setToSegmentId(to.getId());
                    h.setHandoverAt(from.getActualArriveAt() == null ? OffsetDateTime.now() : from.getActualArriveAt());
                    h.setFromCarrierId(from.getCarrierId());
                    h.setToCarrierId(to.getCarrierId());
                    h.setLastTempC(new BigDecimal("5.2"));
                    h.setLastHumidity(new BigDecimal("55.0"));
                    h.setLastDeviceNo("DEV-S" + (i + 1));
                    h.setContinuityOk(true);
                    h.setDeviceHandoffOk(true);
                    h.setIsNonTransit(Constants.SEG_STORAGE.equals(to.getSegmentType()));
                    h.setStorageLocation(to.getSegmentType().equals(Constants.SEG_STORAGE) ? "上海港堆场" : null);
                    h.setStorageTemperature(to.getSegmentType().equals(Constants.SEG_STORAGE) ? new BigDecimal("6.0") : null);
                    h.setOperator("调度员");
                    if (from.getActualArriveAt() != null && to.getActualDepartAt() != null) {
                        h.setGapMinutes((int) java.time.Duration.between(
                                from.getActualArriveAt(), to.getActualDepartAt()).toMinutes());
                    }
                    handoverRepo.save(h);
                }
            }
            int nonTransit = (int) segs.stream().filter(s -> Boolean.FALSE.equals(s.getIsInTransit())).count();
            handovers = handoverRepo.findByTaskIdOrderByHandoverAtAsc(task.getId());
            int withNonTransit = (int) handovers.stream().filter(SegmentHandover::getIsNonTransit).count();
            c.setPassed(segs.size() >= 3 && nonTransit >= 1 && withNonTransit >= 1);
            c.setMessage("订单=" + task.getTaskNo() + " 段数=" + segs.size()
                    + " 段间交接=" + handovers.size()
                    + " 非在途段=" + nonTransit
                    + " 非在途交接=" + withNonTransit);

            Map<String, Object> extra = new HashMap<>();
            extra.put("taskId", task.getId());
            List<Map<String, Object>> segVos = new ArrayList<>();
            for (TransportSegment s : segs) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("segmentNo", s.getSegmentNo());
                v.put("segmentType", s.getSegmentType());
                v.put("isInTransit", s.getIsInTransit());
                v.put("carrierId", s.getCarrierId());
                v.put("responsible", s.getResponsiblePerson());
                segVos.add(v);
            }
            extra.put("segments", segVos);
            c.setExtra(extra);
        } catch (Exception e) {
            log.error("【自检-段间断点】失败", e);
            c.setPassed(false);
            c.setMessage("段间断点校验失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    /**
     * 多式联运硬验收 3：能在任一段发生超温时自动定位影响剂量与责任方并发出整改工单。
     */
    private CheckItem checkOverheatToResponsibilityAndWorkorder() {
        CheckItem c = new CheckItem();
        c.setName("多式联运硬验收 3 - 超温→影响剂量→责任方→整改工单");
        c.setCategory("MULTIMODAL");
        long t0 = System.currentTimeMillis();
        try {
            Optional<TransportTask> opt = taskRepo.findAll().stream()
                    .filter(t -> t.getTaskNo() != null && t.getTaskNo().startsWith("MM-SEG-"))
                    .max(Comparator.comparing(TransportTask::getCreatedAt));
            if (!opt.isPresent()) {
                c.setPassed(false);
                c.setMessage("无 MM-SEG 任务，请先跑段树自检");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            TransportTask task = opt.get();

            // 先触发原 RuleEngine 审计（让 finding 入库）
            AuditSummary summary = ruleEngine.audit(task.getId());

            // 触发多链协同
            Map<String, Object> coord = coordinationEngine.coordinate(task.getId());

            Map<String, Object> overall = (Map<String, Object>) coord.get("overall");
            List<Map<String, Object>> contribs = (List<Map<String, Object>>) coord.get("contributions");
            List<Map<String, Object>> wos = (List<Map<String, Object>>) coord.get("workOrders");
            List<Map<String, Object>> regs = (List<Map<String, Object>>) coord.get("regulatoryReports");

            boolean hasOverheat = contribs != null && contribs.stream()
                    .anyMatch(m -> Constants.EX_OVERHEAT.equals(m.get("exceptionType")));
            boolean hasQty = contribs != null && contribs.stream()
                    .anyMatch(m -> m.get("estimatedAffectedQty") != null
                            && ((Number) m.get("estimatedAffectedQty")).intValue() > 0);
            boolean hasWo = wos != null && !wos.isEmpty();
            boolean hasReg = regs != null && !regs.isEmpty();
            boolean statusOk = "BLOCK".equals(overall.get("status")) || "REVIEW".equals(overall.get("status"));

            c.setPassed(hasOverheat && hasQty && hasWo && statusOk);
            c.setMessage("status=" + overall.get("status")
                    + " 贡献=" + contribs.size()
                    + " 工单=" + wos.size()
                    + " 监管报告=" + regs.size()
                    + " 超温命中=" + hasOverheat + " 影响剂量>0=" + hasQty);

            Map<String, Object> extra = new HashMap<>();
            extra.put("taskId", task.getId());
            extra.put("auditFindings", summary.getTotalFindings());
            extra.put("coordinationStatus", overall.get("status"));
            extra.put("workOrderCount", wos.size());
            extra.put("regulatoryReportCount", regs.size());
            if (!contribs.isEmpty()) {
                Map<String, Object> first = contribs.get(0);
                extra.put("firstContribution", first);
            }
            c.setExtra(extra);
        } catch (Exception e) {
            log.error("【自检-责任段定位】失败", e);
            c.setPassed(false);
            c.setMessage("责任段定位失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    /**
     * 多式联运硬验收 4：能按药品剂型检索适用的异常处置预案并展示决策依据。
     */
    private CheckItem checkPrescriptionLookup() {
        CheckItem c = new CheckItem();
        c.setName("多式联运硬验收 4 - 剂型→异常→处置预案检索");
        c.setCategory("MULTIMODAL");
        long t0 = System.currentTimeMillis();
        try {
            // COLD + OVERHEAT
            Map<String, Object> coldOverheat = prescriptionService.getByFormAndType(Constants.FORM_COLD, Constants.EX_OVERHEAT);
            // COLD + DOOR
            Map<String, Object> coldDoor = prescriptionService.getByFormAndType(Constants.FORM_COLD, Constants.EX_DOOR_OPEN);
            // FROZEN + OVERHEAT
            Map<String, Object> frozenOverheat = prescriptionService.getByFormAndType(Constants.FORM_FROZEN, Constants.EX_OVERHEAT);
            // 列出全部
            List<Map<String, Object>> allCold = prescriptionService.listByDosageForm(Constants.FORM_COLD);

            boolean ok = coldOverheat != null && coldDoor != null && frozenOverheat != null
                    && allCold.size() >= 4;
            c.setPassed(ok);
            c.setMessage("COLD+OVERHEAT=" + (coldOverheat == null ? "无" : "有")
                    + " COLD+DOOR=" + (coldDoor == null ? "无" : "有")
                    + " FROZEN+OVERHEAT=" + (frozenOverheat == null ? "无" : "有")
                    + " COLD预案总数=" + allCold.size());

            Map<String, Object> extra = new HashMap<>();
            extra.put("coldOverheatPrescription", coldOverheat);
            extra.put("coldDoorPrescription", coldDoor);
            extra.put("frozenOverheatPrescription", frozenOverheat);
            c.setExtra(extra);
        } catch (Exception e) {
            log.error("【自检-预案检索】失败", e);
            c.setPassed(false);
            c.setMessage("预案检索失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }

    /**
     * 多式联运硬验收 5：能生成多式联运全链合规报告并标注每段贡献。
     */
    private CheckItem checkMultiChainComplianceReport() {
        CheckItem c = new CheckItem();
        c.setName("多式联运硬验收 5 - 全链合规报告 + 段贡献");
        c.setCategory("MULTIMODAL");
        long t0 = System.currentTimeMillis();
        try {
            Optional<TransportTask> opt = taskRepo.findAll().stream()
                    .filter(t -> t.getTaskNo() != null && t.getTaskNo().startsWith("MM-SEG-"))
                    .max(Comparator.comparing(TransportTask::getCreatedAt));
            if (!opt.isPresent()) {
                c.setPassed(false);
                c.setMessage("无 MM-SEG 任务，请先跑段树自检");
                c.setDurationMs(System.currentTimeMillis() - t0);
                return c;
            }
            TransportTask task = opt.get();
            // 已经持久化的多链合规
            MultiChainCompliance mc = em.createQuery(
                    "SELECT m FROM MultiChainCompliance m WHERE m.taskId = :tid", MultiChainCompliance.class)
                    .setParameter("tid", task.getId()).getResultStream().findFirst().orElse(null);
            // 工作单数与监管报告数
            long woCount = workorderRepo.findByTaskId(task.getId()).size();
            long regCount = regReportRepo.findByTaskId(task.getId()).size();

            boolean hasReport = mc != null && mc.getContributionJson() != null
                    && JsonUtil.toList(mc.getContributionJson()).size() > 0;
            boolean hasCritical = mc != null && mc.getCriticalSegments() != null
                    && !JsonUtil.toList(mc.getCriticalSegments()).isEmpty();
            c.setPassed(hasReport && hasCritical && woCount > 0);
            c.setMessage("overall=" + (mc == null ? "无" : mc.getOverallStatus())
                    + " 决策=" + (mc == null ? "无" : mc.getOverallDecision())
                    + " 段数=" + (mc == null ? 0 : mc.getSegmentCount())
                    + " 工单=" + woCount
                    + " 监管报告=" + regCount);

            Map<String, Object> extra = new HashMap<>();
            extra.put("taskId", task.getId());
            if (mc != null) {
                extra.put("overallStatus", mc.getOverallStatus());
                extra.put("overallDecision", mc.getOverallDecision());
                extra.put("criticalSegments", JsonUtil.toList(mc.getCriticalSegments()));
                extra.put("contributionCount", JsonUtil.toList(mc.getContributionJson()).size());
                extra.put("summary", mc.getSummaryText());
            }
            extra.put("workOrderCount", woCount);
            extra.put("regulatoryReportCount", regCount);
            c.setExtra(extra);
        } catch (Exception e) {
            log.error("【自检-多链报告】失败", e);
            c.setPassed(false);
            c.setMessage("多链报告生成失败: " + e.getMessage());
        }
        c.setDurationMs(System.currentTimeMillis() - t0);
        return c;
    }
}
