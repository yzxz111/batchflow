# BatchFlow 实际验证记录

记录日期：2026-11-25。本轮完整复验为 **16:43:07–16:45:26（北京时间）**，运行标识 `20261125-closeout-02`，本机验收状态为 **PASSED**。这不包含尚未实跑的 Docker Compose 或远端 GitHub Actions。

原始证据已固定至 [docs/evidence/20261125-closeout-02](evidence/20261125-closeout-02/report.json)，包含测试 JSON、Surefire XML/TXT、浏览器 trace、9 张截图、性能原始分项、RabbitMQ 断言与应用日志。文件 SHA-256 与受测源码快照见 [manifest.json](evidence/20261125-closeout-02/manifest.json)。复验与导出方法见 [交付说明](DELIVERY.md)。

---

## 1. 本机工具链与环境检查

执行 `scripts/check-env.ps1` 与 `scripts/Start-Database.ps1`：

- **JDK**：Eclipse Adoptium OpenJDK `21.0.13.1+2`
- **Maven**：Apache Maven `3.9.11`
- **Node.js / pnpm**：Node `v24.22.3`（通过 `scripts/env.ps1` 进程级接入）+ `pnpm`（`.tools/bin` 本地 shim，`web/pnpm-workspace.yaml` 配置 `allowBuilds: { esbuild: true }`）
- **PostgreSQL**：PostgreSQL `17.7`（独立集群运行于 `127.0.0.1:15439`，业务库 `batchflow` 与测试库 `batchflow_test` 已就绪）
- **Git**：MinGit `2.56.1.windows.1`（便携安装于 `.tools/git`，已通过 `scripts/env.ps1` 接入）
- **Erlang / RabbitMQ**：Erlang OTP `27.3.4.20`（`.tools/erlang`）+ RabbitMQ Server `4.4.1`（`.tools/rabbitmq`，数据与日志隔离在 `.runtime/rabbitmq`）
- **浏览器自动化**：Python `3.12.10` + `playwright`（驱动本机 Chrome 无头模式）
- **硬件**：AMD Ryzen 7 8745H，8 核/16 线程；系统报告可见内存约 19.75 GiB，Windows 11 64 位。原始环境信息见 [environment.json](evidence/20261125-closeout-02/environment.json)。
- **受限项**：本轮实际 Docker 预检因找不到 `docker` CLI 返回 `BLOCKED`、退出码 1；未执行容器构建或 Compose 运行。不以 Windows 版本推断 Docker 是否可用。

---

## 2. 自动化测试、构建与等价 CI 校验

本地已初始化 Git 仓库并按 `.github/workflows/ci.yml` 的校验步骤顺序执行通过（尚未配置远程 GitHub 仓库）：

### 前端（`web/`）

执行 `pnpm install --frozen-lockfile && pnpm test && pnpm build`：

- `pnpm install --frozen-lockfile`：通过（补充开发依赖 `@vue/test-utils 2.6.0` 与 `happy-dom 21.0.4`）。
- `pnpm test`（`vitest run`）：**3 个测试文件全部通过，87 passed (87)**：
  - `src/utils.test.ts`（76 tests）：覆盖 `guessMapping`、`mappingError`、`progress`、`duration`、`number`（含 `NaN`/`Infinity` 守卫）、`dateTime`。
  - `src/api.test.ts`（7 tests）：覆盖 `NETWORK_ERROR`、`INVALID_RESPONSE`、结构化 HTTP 错误解析、`Idempotency-Key` 请求头、查询参数编码、`requestBlob`、`saveBlob` URL 释放与 `errorMessage`。
  - `src/components.test.ts`（4 tests）：使用 `@vue/test-utils` + `happy-dom` 覆盖 `StatusBadge`、`TaskTable`、`ImportWizard`（示例文件上传、映射、幂等提交）与 `TaskDrawer`（取消确认、失败重试、错误列表与 CSV 导出）。
- `pnpm build`（`vue-tsc --noEmit && vite build`）：类型检查 0 错误，构建产物输出至 `web/dist/`。

### 后端（`backend/`）

执行 `scripts/Verify-Local.ps1 -ReportDirectory .runtime/evidence/local/20261125-closeout-02 -SkipInstall`，其中后端运行 `mvn -B -f backend/pom.xml verify`：**BUILD SUCCESS**，共运行 **33 个测试，0 失败、0 错误、0 跳过**。本次依赖已在此前按锁文件安装，`closeout-01` 的安装步骤已实际通过。

- `com.batchflow.CsvAndValidatorTest`（22 tests）：
  - UTF-8 BOM 解析、带引号逗号及字段内换行单逻辑记录计数；
  - 非法 UTF-8 字节序列、重复表头、空白表头拒绝；
  - `CsvFiles.safeCell` 对 `=`、`+`、`-`、`@`（含前导空格）及 `\t`、`\r`、`\n` 的公式注入转义；
  - `ProductValidator` 对 SKU、商品名称、分类、价格（正数、最多两位小数、上限）与库存边界的校验。
- `com.batchflow.BatchFlowIntegrationTest`（4 tests，连接真实 PostgreSQL `batchflow_test`）：
  - `/api/health`探活、`/api/templates/products.csv` 模板下载、405 `METHOD_NOT_ALLOWED` 真实状态码映射、含 BOM/换行/重复 SKU/公式前缀的端到端导入及 `/api/tasks/{id}/errors.csv` 导出核对、`/api/products` 与 `/api/overview` 核对；
  - `Idempotency-Key` 相同语义重放返回同任务、不同请求返回 HTTP `409 CONFLICT`；
  - 租约过期后新 Worker 抢占与 `lease_token` fencing：旧 Worker 提交批次与调用 `finish()` 均抛出 `LeaseLostException`，`heartbeat()` 返回 `false`，新 Worker 从断点续传完成且无重复入库；
  - 取消任务阻断活跃 Worker 后续提交；`FAILED` 任务手动重试保留已提交检查点并重置 `attempt`。
- `com.batchflow.WorkerRunnerAndRabbitDispatchTest`（7 tests）：
  - 针对 `WorkerRunner.java` 并发锁与消费异常风险的修复与验证：使用 `ReentrantLock` 替代非阻塞 `AtomicBoolean` 静默返回，验证 `poll()` 占用执行锁期间并发调用 `receive(taskId)` 会等待并完成领取处理而非丢弃消息；
  - 验证 `receive(taskId)` 在 `engine.claim(...)` 抛出异常时短暂退避后重新抛出至 AMQP 容器边界，而 `poll()` 保持异常捕获与日志记录不中断调度线程；该项单元测试不代表真实 Broker NACK/requeue 已故障注入；
  - 验证 `database`/`rabbitmq` 两种队列模式下 `onlyExpired` 参数路由、`RabbitDispatch.Listener` 异常透传，以及 `RabbitDispatch.Publisher` 在 broker ACK 时标记 `published_at`、在 returned/unroutable 时更新 `attempts` 与 `last_error`。
  - 新增模拟 `Confirm(false, ...)` NACK 和真实未完成 Future 等待 5 秒超时的分支验证。NACK、Return 和超时为 Mockito/逻辑层覆盖。

前端原始结果：[frontend-vitest.json](evidence/20261125-closeout-02/frontend-vitest.json)；后端原始结果：[CSV/校验 22 项](evidence/20261125-closeout-02/backend-tests/com.batchflow.CsvAndValidatorTest.txt)、[真实数据库集成 4 项](evidence/20261125-closeout-02/backend-tests/com.batchflow.BatchFlowIntegrationTest.txt)、[Worker/Publisher 7 项](evidence/20261125-closeout-02/backend-tests/com.batchflow.WorkerRunnerAndRabbitDispatchTest.txt)。

---

## 3. RabbitMQ 队列档（`app.queue-mode=rabbitmq`）真实 Broker 实测

使用便携安装在本机的 **RabbitMQ Server 4.4.1**（`127.0.0.1:5672`）+ PostgreSQL 17.7 + 独立 API（`18090`）与 Worker（`18091`）执行 `scripts/Verify-RabbitMQ.ps1 -ReportDirectory <本轮新目录>`，全部通过。可逐项复核 [RabbitMQ report.json](evidence/20261125-closeout-02/rabbitmq/report.json) 与 [任务原始结果](evidence/20261125-closeout-02/rabbitmq/tasks.json)。

1. **Outbox → RabbitMQ Broker → Worker 完整链路**：
   - API 与 Worker `/api/health` 均返回 `queueMode: "rabbitmq"`；
   - 创建 120 行混合任务（`19fc7644-6173-489c-b170-9b0a68b74669`），通过 `batchflow.tasks` / `batchflow.imports` 真实投递后由 Worker 消费完成（`COMPLETED_WITH_ERRORS`，成功 `116`，错误 `4`）。
2. **单 Worker 接收并发提交任务**：
   - 向单个处于忙碌处理的 Worker（`batch-delay-ms=250`、`poll-ms=250`）并发提交 **4 个任务**（每个 200 行、批大小 50）；
   - 4 个任务全部进入 `COMPLETED`（成功 `200`、错误 `0`、检查点 `200`），无任务滞留 `QUEUED`。脚本对目标队列存在、`messages/ready/unacknowledged` 均为 `0`、Outbox 未投递数为 `0` 做了强制断言，并检查外部命令退出码。确定性的轮询/消费者锁竞争另由单元测试覆盖；四任务提交不代表多 Worker 并行。
3. **RabbitMQ 队列档 Worker 强停故障恢复**：
   - 创建 600 行任务（`a22ecef9-8077-44c4-86bd-38f71ea15adb`，批大小 `100`、租约 `8` 秒）。强停前查询观察到 `checkpoint=100`，实际停止后数据库已持久化到 `200`；两者之间允许已提交一批，报告分别保存观察值与停机后值。
   - 以 `rabbitmq` 模式重启 Worker，旧租约过期后从第 `200` 行接管，断言恢复事件的检查点等于停机后持久化值；最终 `COMPLETED_WITH_ERRORS`，处理 `600`、成功 `576`、错误 `24`、检查点 `600`、`attempt=2`，商品数精确为 `576`。该项证明 RabbitMQ 模式下的租约接管，不扩大为 Broker 断网、重启或真实 NACK/Return 场景均已通过。

---

## 4. 双进程故障恢复实机演练（`database` 队列档）

本节为当天 15:04–15:05 的历史恢复演练，任务与事件仍保存在本项目数据库；本轮另重新运行了数据库集成测试与上节的真实 RabbitMQ 模式恢复。

1. 生成合成样本：
   `node scripts/generate-sample.mjs --rows 1200 --invalid-every 25 --output .runtime/samples/recovery-verify.csv`
   （预期有效行 `1152`、非法价格行 `48`，SKU 前缀 `DEMO-82f1d66a`）。
2. 以 `-BatchDelayMs 1500` 启动独立 API（`127.0.0.1:18090`）与 Worker（`127.0.0.1:18091`）进程，上传样本并创建批大小 `200`、最大尝试 `3` 的任务 `4d4b94c0-db0d-46cc-bec8-baf1aeb74e6d`。
3. 在任务运行至 `processedRows=200`、`checkpoint=200`、`attempt=1`（`worker-aebf41fa-86e`）时，调用 `Stop-TaskflowProcess -Role worker` 强制停止 Worker 进程。
4. 停止后核对 API：任务保持 `RUNNING`，已提交 `processedRows=200`、`checkpoint=200` 未丢失。
5. 调用 `scripts/Restart-Worker.ps1 -BatchDelayMs 200` 重启新 Worker（`worker-87ddab9c-ce3`），待旧 15 秒租约过期后自动接管并完成导入：
   - 最终状态：`COMPLETED_WITH_ERRORS`
   - 行数不变量：`processedRows=1200`，`successRows=1152`，`errorRows=48`，`checkpoint=1200`，`attempt=2`
   - 任务事件流包含 `CREATED` → `STARTED`（第 0 行继续） → `RECOVERED`（第 200 行检查点继续） → `COMPLETED`
   - 查询 `/api/products?q=DEMO-82f1d66a` 返回总数为精确 `1152`，无重复、无遗漏。

---

## 5. 浏览器工作台 E2E 自动化验收

使用 Playwright 驱动本机 Chrome 对真实 API（`127.0.0.1:18090`）+ Worker（`18091`，`-BatchDelayMs 400`）+ PostgreSQL 执行 `scripts/e2e-browser.py --report-dir <本轮新目录>`，**7 项功能 + 1 项布局 + 1 项诊断，共 9/9 通过**。非故意断网阶段控制台错误/页面异常均为 `0`；故意断网阶段的 `1` 条控制台网络错误单独记录。原始结果与测量值见 [E2E report.json](evidence/20261125-closeout-02/e2e/report.json)，完整 trace 和 9 张截图在同目录。

1. **总览与执行节点**：指标卡片、`database 本地队列` 与 `在线 Worker` 状态展示正常；
2. **示例模板下载核对**：点击「下载示例 CSV」捕获真实文件下载，校验 UTF-8 BOM（`\xef\xbb\xbf`）、表头 `sku,name,category,price,stock` 与示例行；
3. **完整导入与错误导出核对**：上传 120 行混合样本（105 合法 + 15 非法，含 `=CMD|'/C calc'!A0` 公式前缀），自动映射 5 个字段后提交，轮询至 `COMPLETED_WITH_ERRORS`（`100.0%`、检查点 `120`）；校验错误明细 2 页分页翻页与「原始数据」展开；下载错误 CSV 校验 UTF-8 BOM、中文表头与 `'=CMD|` 防注入转义；
4. **商品检索与溯源**：在「已入库商品」页搜索入库 SKU 并点击来源任务跳转回详情抽屉；
5. **运行中取消任务**：提交 400 行任务（批大小 40）后在 `RUNNING` 阶段点击「取消任务」→「确认取消任务」，核对状态转为 `CANCELLED` 且 2.5 秒内已处理行数不再增长；
6. **失败任务手动重试**：以缺失存储文件触发 `maxAttempts=1` 任务在检查点 `0` 进入 `FAILED`，恢复文件后点击「从检查点重试」，最终为 `COMPLETED`（20/20 行）。该浏览器场景验证重试按钮流程；非零检查点恢复由后端集成与 Worker 强停演练提供证据；
7. **断网错误提示与恢复**：离线状态下刷新触发真实错误横幅（`暂时无法连接服务，请确认后端已启动后重试。`），恢复网络点击「重新加载」后横幅消失；
8. **窄屏响应式布局（`390×844`）**：三种界面均重新测量整页与目标元素。总览 `scrollWidth/clientWidth=390/390`，向导 `370/370`，详情抽屉 `390/390`；三者整页宽度均为 `390`，无横向溢出。

---

## 6. 单机吞吐性能实测（`database` 队列档）

在 `BatchDelayMs=0`、单 API 进程 + 单 Worker 进程 + 本机 PostgreSQL 17.7 条件下执行 `scripts/benchmark.mjs`（每 25 行含 1 条非法价格数据），全部任务的 `processedRows = successRows + errorRows = checkpoint` 不变量校验通过：

| 压测场景 | 总行数 | 批大小 | 文件体积 | 上传耗时 | Worker 处理耗时 | Worker 吞吐 | 端到端吞吐 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `10k-rows` | 10,000（9,600 成功 / 400 错误） | 500 | 0.54 MiB | 91.473 ms | 2,554 ms | ~3,915 rows/s | ~3,299 rows/s |
| `100k-rows` | 100,000（96,000 成功 / 4,000 错误） | 1,000 | 5.61 MiB | 257.885 ms | 20,028 ms | ~4,993 rows/s | ~4,724 rows/s |
| `concurrent-3x5000` | 3 并发任务共 15,000 行 | 500 | — | — | 总墙钟耗时 5,632.163 ms | — | 聚合 ~2,663 rows/s |

原始输入哈希、任务 ID、服务端时间、客户端各阶段计时与逐任务不变量见 [benchmark/report.json](evidence/20261125-closeout-02/benchmark/report.json)。端到端分母包含本地读文件/FormData、上传、创建、排队、执行和轮询观测，不包含 CSV 生成；Worker 耗时按持久化开始/完成时间计算，受 JavaScript 毫秒精度影响。并发为三任务并发提交、单 Worker 执行。本表采用本轮实测值，不挑选此前更快的一次结果。

> 边界说明：以上数据仅代表**单机 Windows + 单 Worker + `database` 队列档**在测试样本下的实测结果，不能外推为多节点集群高并发或 100 万行极限文件已实测通过。

---

## 7. 当前仍受限于系统环境的未实测项

- **Docker Compose**：已补齐隔离容器冒烟入口 `scripts/verify-compose.mjs`，并修正 Dockerfile 在安装前复制 pnpm workspace 配置。Node 语法、YAML 静态解析通过；实际预检为 [BLOCKED / docker_preflight](evidence/20261125-closeout-02/container-preflight/report.json)，因为没有 Docker CLI。未执行镜像构建、`compose config` 或真实容器验收。
- **远端 GitHub Actions**：已补齐前后端原始报告归档、隔离 Compose job 和失败时 artifacts 上传；本地没有用户指定的 remote，远端工作流尚未触发。登录信息不能代替具体仓库地址。

## 8. 交付证据与本轮修复

- 本轮首次尝试 `closeout-01` 已通过测试，但在启动阶段因 PowerShell 7 将 JSON 时间自动解析为 DateTime、旧脚本又二次解析而产生 8 小时时差，误判 PID 重用。本轮已修复时间解析并保持 PID/角色/jar/创建时间校验；`closeout-02` 在 PowerShell 7 完整通过，Windows PowerShell 5.1 对真实进程检查也通过。首次失败摘要仍保留在证据目录的 `previous-attempt/`，没有覆盖成成功。
- `Verify-Local.ps1` 在测试前保存源码与构建配置哈希，结束再次校验；`Export-Evidence.ps1` 只有在当前源码仍匹配时才能导出，避免把后改的代码冒充受测版本。完整证据清单为 56 个文件，另含清单文件本身。
- 验收后的只读数据库快照：39 个合成/演示任务、246,226 个商品与同数唯一 SKU；任务不变量异常、任务成功数与商品数不一致、任务错误数与错误明细不一致、Outbox 未投递数均为 `0`。这是本项目累计测试数据，不能当作单次压测规模或真实业务记录。见 [database-snapshot.json](evidence/20261125-closeout-02/database-snapshot.json)。
- 本轮结束 API/Worker 均已停止，RabbitMQ 正常清理，PostgreSQL 与既有数据保留。所有通过状态均限于报告明示的本机范围。
