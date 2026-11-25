# BatchFlow 实际验证记录

记录日期：2026-11-25。本文件严格区分**本机实测已通过项目**与**因系统权限/环境限制暂未实机运行项目**。

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
- **受限项**：当前运行环境为 Windows 11 家庭版且无管理员 UAC 提权，未安装 Docker Desktop / WSL2。

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

执行 `scripts/mvn.ps1 -B -f backend/pom.xml verify`：**BUILD SUCCESS**，共运行 **31 个测试，0 失败，0 跳过**：

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
- `com.batchflow.WorkerRunnerAndRabbitDispatchTest`（5 tests）：
  - 针对 `WorkerRunner.java` 并发锁与消费异常风险的修复与验证：使用 `ReentrantLock` 替代非阻塞 `AtomicBoolean` 静默返回，验证 `poll()` 占用执行锁期间并发调用 `receive(taskId)` 会等待并完成领取处理而非丢弃消息；
  - 验证 `receive(taskId)` 在 `engine.claim(...)` 抛出异常时短暂退避后重新抛出，交由 Spring AMQP NACK/requeue，而 `poll()` 保持异常捕获与日志记录不中断调度线程；
  - 验证 `database`/`rabbitmq` 两种队列模式下 `onlyExpired` 参数路由、`RabbitDispatch.Listener` 异常透传，以及 `RabbitDispatch.Publisher` 在 broker ACK 时标记 `published_at`、在 returned/unroutable 时更新 `attempts` 与 `last_error`。

---

## 3. RabbitMQ 队列档（`app.queue-mode=rabbitmq`）真实 Broker 实测

使用便携安装在本机的 **RabbitMQ Server 4.4.1**（`127.0.0.1:5672`）+ PostgreSQL 17.7 + 独立 API（`18090`）与 Worker（`18091`）执行 `scripts/Verify-RabbitMQ.ps1`，全部通过：

1. **Outbox → RabbitMQ Broker → Worker 完整链路**：
   - API 与 Worker `/api/health` 均返回 `queueMode: "rabbitmq"`；
   - 创建 120 行混合任务（`92d93516-aae7-4349-8e6e-348aac01ccff`），`RabbitDispatch.Publisher` 通过 Publisher Confirm 确认投递到 `batchflow.tasks` / `batchflow.imports`，Worker 消费完成（`COMPLETED_WITH_ERRORS`，成功 `116`，错误 `4`）。
2. **`WorkerRunner` 并发忙碌防丢消息复测**：
   - 向单个处于忙碌处理的 Worker（`batch-delay-ms=250`、`poll-ms=250`）并发提交 **4 个任务**（每个 200 行、批大小 50）；
   - 4 个任务全部进入 `COMPLETED`（成功 `200`、检查点 `200`），无任务滞留 `QUEUED`；结束时 `task_outbox` 未投递数为 `0`，`rabbitmqctl list_queues` 显示 `batchflow.imports` 积压为 `0`。
3. **RabbitMQ 队列档 Worker 强停故障恢复**：
   - 创建 600 行任务（`b822e937-ee08-42c5-959f-92bb5fe7e7b9`，批大小 `100`、租约 `8` 秒），在 `RUNNING` 且 `checkpoint=100` 时强制停止 Worker；
   - 以 `rabbitmq` 模式重启 Worker，旧租约过期后 `poll()` 从第 `200` 行检查点接管并完成（`COMPLETED_WITH_ERRORS`，`processedRows=600`，`successRows=576`，`errorRows=24`，`attempt=2`），商品查询精确为 `576` 条，事件流完整记录 `CREATED → STARTED → RECOVERED → COMPLETED`。

---

## 4. 双进程故障恢复实机演练（`database` 队列档）

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

使用 Playwright 驱动本机 Chrome 对真实 API（`127.0.0.1:18090`）+ Worker（`127.0.0.1:18091`，`-BatchDelayMs 400`）+ PostgreSQL 执行 `scripts/e2e-browser.py`，全部 9 项场景断言通过，控制台错误 0 条、未捕获页面异常 0 条，截图留存于 `.runtime/e2e-screenshots/`：

1. **总览与执行节点**：指标卡片、`database 本地队列` 与 `在线 Worker` 状态展示正常；
2. **示例模板下载核对**：点击「下载示例 CSV」捕获真实文件下载，校验 UTF-8 BOM（`\xef\xbb\xbf`）、表头 `sku,name,category,price,stock` 与示例行；
3. **完整导入与错误导出核对**：上传 120 行混合样本（105 合法 + 15 非法，含 `=CMD|'/C calc'!A0` 公式前缀），自动映射 5 个字段后提交，轮询至 `COMPLETED_WITH_ERRORS`（`100.0%`、检查点 `120`）；校验错误明细 2 页分页翻页与「原始数据」展开；下载错误 CSV 校验 UTF-8 BOM、中文表头与 `'=CMD|` 防注入转义；
4. **商品检索与溯源**：在「已入库商品」页搜索入库 SKU 并点击来源任务跳转回详情抽屉；
5. **运行中取消任务**：提交 400 行任务（批大小 40）后在 `RUNNING` 阶段点击「取消任务」→「确认取消任务」，核对状态转为 `CANCELLED` 且 2.5 秒内已处理行数不再增长；
6. **失败任务从检查点重试**：以缺失存储文件触发 `maxAttempts=1` 任务进入 `FAILED` 并展示「最近执行异常」，恢复文件后点击「从检查点重试」，核对最终状态转为 `COMPLETED`（20/20 行）；
7. **断网错误提示与恢复**：离线状态下刷新触发真实错误横幅（`暂时无法连接服务，请确认后端已启动后重试。`），恢复网络点击「重新加载」后横幅消失；
8. **窄屏响应式布局（`390×844`）**：验证并修复了 `.table-wrap` 内 `.sr-only` 与 Flex 容器导致的 246px 横向溢出问题，实测总览页、导入向导弹窗与任务详情抽屉在 `390px` 视口下无横向溢出（`scrollWidth <= innerWidth + 1`）。

---

## 6. 单机吞吐性能实测（`database` 队列档）

在 `BatchDelayMs=0`、单 API 进程 + 单 Worker 进程 + 本机 PostgreSQL 17.7 条件下执行 `scripts/benchmark.mjs`（每 25 行含 1 条非法价格数据），全部任务的 `processedRows = successRows + errorRows = checkpoint` 不变量校验通过：

| 压测场景 | 总行数 | 批大小 | 文件体积 | 上传耗时 | Worker 处理耗时 | Worker 吞吐 | 端到端吞吐 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `10k-rows` | 10,000（9,600 成功 / 400 错误） | 500 | 0.54 MiB | 119 ms | 1,826 ms | ~5,476 rows/s | ~4,359 rows/s |
| `100k-rows` | 100,000（96,000 成功 / 4,000 错误） | 1,000 | 5.61 MiB | 174 ms | 15,281 ms | ~6,544 rows/s | ~6,160 rows/s |
| `concurrent-3x5000` | 3 并发任务共 15,000 行 | 500 | — | — | 总墙钟耗时 5,195 ms | — | 聚合 ~2,887 rows/s |

> 边界说明：以上数据仅代表**单机 Windows + 单 Worker + `database` 队列档**在测试样本下的实测结果，不能外推为多节点集群高并发或 100 万行极限文件已实测通过。

---

## 7. 当前仍受限于系统环境的未实测项

- **Docker Desktop / Compose 容器编排（`deploy/compose.yaml`）**：当前 Windows 11 家庭版环境无管理员 UAC 提权且未启用 WSL2 重启，Docker Desktop 无法在此会话内静默完成内核安装与重启；`deploy/compose.yaml` 与 `deploy/Dockerfile` 仍待具备 Docker 的机器实机验证。
- **远端 GitHub Actions CI 触发**：本地已完成 `git init` 初始化与等价全流程构建/测试校验，但未配置用户指定的远程仓库，`.github/workflows/ci.yml` 尚未在 GitHub 远端运行。
