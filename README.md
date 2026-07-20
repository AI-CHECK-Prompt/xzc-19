# 跨境冷链 GxP 合规系统

> **药监局飞行检查（Flight Inspection）专用版本**  
> 覆盖三大缺口：温控数据真实性 / 审计规则引擎 / 放行决策闭环

## 一键启动

```bash
# Windows
scripts\start.bat

# Linux / Mac
bash scripts/start.sh
```

启动后访问：
- **前端控制台**: http://localhost:8080/
- **接口自检**: http://localhost:8080/api/self-check
- **健康检查**: http://localhost:8080/api/self-check/ping

默认账号（密码统一为 `password123`）：
- `admin`（管理员）
- `dispatcher`（调度员）
- `auditor`（审计员）
- `qa`（质量负责人）
- `customs`（海关对接员）

## 系统架构

```
┌──────────────┐    HTTP/MQTT    ┌─────────────────┐
│  100+ 温控    │ ───────────────▶│  Ingest 接入     │
│  记录仪       │   每10s-5min    │  (时钟对齐+签名) │
└──────────────┘                 └────────┬────────┘
                                          │ WORM + 哈希链
                                          ▼
                                ┌─────────────────────┐
                                │  PostgreSQL 15      │
                                │  + TimescaleDB      │
                                │  不可篡改存储        │
                                └────────┬────────────┘
                                         │
                ┌────────────────────────┼─────────────────────┐
                ▼                        ▼                     ▼
        ┌──────────────┐      ┌──────────────────┐    ┌──────────────┐
        │  Rule Engine │      │  Customs Parser  │    │  Replay      │
        │  6 类自动审计 │      │  报关单 + 批号匹配│    │  时间轴回放   │
        └──────┬───────┘      └────────┬─────────┘    └──────────────┘
               ▼                       ▼
        ┌──────────────────────────────────────────┐
        │           Decision 放行决策闭环           │
        │   温度×海关×检验  → RELEASE/BLOCK/条件    │
        └──────────────────────────────────────────┘
               │
               ▼
        ┌──────────────────────────────────────────┐
        │  飞检工具：XML/PDF/Excel 导出 + 多维检索    │
        └──────────────────────────────────────────┘
```

## 关键能力

### 1. 温控数据真实性（缺口一）
- **上链式上传**：GPRS/4G HTTP/MQTT 上报，10s-5min 粒度
- **不可篡改存储**：
  - 每条记录 SHA-256 哈希 + 哈希链（`prev_hash`）
  - RSA-2048 签名（`signature`）
  - DB 触发器禁止 `UPDATE` / `DELETE`（WORM）
- **时钟对齐**：`temp_recorder.clock_skew_ms` 校正设备漂移

### 2. 审计规则引擎（缺口二）
- **6 类自动审计**：
  - `CONTINUITY` 温度连续性（最大中断秒数）
  - `CUMULATIVE` 累积偏差（越界持续累计时长）
  - `RANGE` 剂型温控范围（2-8℃ / -20℃ / 15-25℃）
  - `DOOR` 门开关合理性（频次 + 单次时长）
  - `TRACK` 轨迹偏移（与计划路线偏差）
  - `SMOOTH` 曲线平滑性（防篡改跳变）
- **剂型自动匹配**：`COLD` / `FROZEN` / `NORMAL`
- **不合规处理**：`BLOCK`（自动拦截） / `REVIEW`（人工审核）
- **结构化证据**：时间区间 / 温度值 / 受影响数量 / 可能后果

### 3. 放行决策闭环（缺口三）
- **多源校验**：审计报告 + 海关报关单 + 检验报告
- **决策三态**：`RELEASE` / `BLOCK` / `CONDITIONAL_RELEASE`
- **全留痕**：决策依据 + 操作日志 + 责任人 + 签名

### 4. 飞检专用工具
- **三格式导出**：XML（JAXB） / PDF（PDFBox） / Excel（POI）
- **多维检索**：按批次 / 任务 / 时间区间
- **完整时间轴回放**：温度曲线 + 轨迹 + 合规检查点 + 决策依据 + 操作日志
- **导出文件签名**：SHA-256 + RSA，可向飞检官员证明文件真实性

## 6 项硬验收

访问 `http://localhost:8080/api/self-check` 自动跑通：

| 序号 | 检查项 | 类别 |
|---|---|---|
| 1 | 数据库连通 | CORE |
| 2 | 规则引擎就绪（6 类齐备）| AUDIT |
| 3 | 100 设备并发接入 | DATA |
| 4 | 含超温任务触发 BLOCK | AUDIT |
| 5 | 哈希链完整性 | IMMUTABLE |
| 6 | WORM 触发器生效 | IMMUTABLE |
| 7 | 三格式导出 | EXPORT |
| 8 | 海关报关单解析与批号匹配 | CORE |

## REST API 速查

| 路径 | 方法 | 说明 |
|---|---|---|
| `/api/auth/login` | POST | 登录获取 JWT |
| `/api/ingest` | POST | 温控/轨迹数据接入 |
| `/api/tasks` | GET/POST | 任务管理 |
| `/api/tasks/{taskNo}/depart` | POST | 发车 |
| `/api/tasks/{taskNo}/arrive` | POST | 到达 |
| `/api/audit/run/{taskNo}` | POST | 触发审计 |
| `/api/audit/decide` | POST | 放行决策 |
| `/api/customs/parse` | POST | 报关单解析+批号匹配 |
| `/api/replay/{taskNo}` | GET | 完整时间轴回放 |
| `/api/search/tasks` | GET | 多维检索 |
| `/api/export/{xml\|pdf\|excel}` | POST | 飞检数据导出 |
| `/api/self-check` | GET | 6 项硬验收自检 |
| `/api/sim/run` | POST | 启动模拟器（百设备并发）|

## 技术栈

- **后端**：Spring Boot 2.7.18 / Java 8 / JPA / Hibernate
- **数据库**：PostgreSQL 15 + TimescaleDB（时序优化）
- **安全**：RSA-2048 / SHA-256 / JWT / BCrypt
- **导出**：Apache POI 5.2.5 / PDFBox 2.0 / JAXB 2.3
- **前端**：Vue 3 + Chart.js（CDN，无构建步骤）
- **模拟器**：Python 3.11（多线程并发）
- **部署**：Docker Compose

## 目录结构

```
.
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/...
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    # Flyway
│   └── docker/backend/Dockerfile
├── frontend/                # Vue3 + Chart.js 前端
│   └── index.html
├── simulator/               # Python 温控设备模拟器
│   ├── simulator.py
│   ├── requirements.txt
│   └── Dockerfile
├── scripts/                 # 一键启动/自检
│   ├── start.bat
│   ├── start.sh
│   └── selfcheck.py
├── docker-compose.yml
└── docs/superpowers/specs/  # 设计文档
```

## 与原需求对照

| 需求 | 实现 |
|---|---|
| 温度连续性 | `RULE_CONTINUITY`（CONTINUITY 类）|
| 偏差闭环 | `RULE_CUMULATIVE`（CUMULATIVE 类）+ 放行决策 |
| 记录可追溯性 | 哈希链 + 签名 + WORM + 时间轴回放 |
| 温湿度/门开关/位置/司机事件 | `temp_sample` 表 |
| 数字签名/WORM | `SignatureUtil` + DB 触发器 |
| 6 类自动审计 | `RuleEngine` 6 个方法 |
| 剂型→温控曲线 | `detectDominantForm` + `dosage_form` |
| 自动拦截 / 标记人工审核 | `BLOCK` / `REVIEW` action |
| 多源校验 | `DecisionService.decide()` |
| 决策依据全程留痕 | `release_decision.basis` JSON + `operation_log` |
| XML/PDF/Excel 导出 | `ExportService` |
| 多维检索 | `SearchService` |
| 审计回放 | `ReplayService.replay()` |
| 5 年不可篡改 | WORM 触发器 |
| 秒级全量审计 | 内存规则引擎，12 点任务 < 100ms |
| 多设备时钟对齐 | `ClockUtil.align()` |
| 多语言报关单解析 | `CustomsParseService`（结构化字段抽取）|
| 100+ 设备并发 | `simulator.py` 多线程 + 自检用例 |
| 一键拉起 | `docker-compose.yml` + `scripts/start.*` |
| 接口自检 | `SelfCheckService` + `/api/self-check` |
