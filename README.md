# BatchFlow · 批量数据处理与任务调度平台

以**商品 CSV 批量导入**为真实业务场景的 Java 后端／全栈作品。用户上传文件、映射字段、提交任务，在工作台查看处理进度和错误明细；API 与 Worker 独立运行，任务状态和已提交批次保存在 PostgreSQL。

项目重点是把“后台处理”做得可解释、可恢复、可验证：每批商品写入、错误行写入与检查点更新使用同一数据库事务，Worker 通过租约领取任务，并以 fencing token 防止过期 Worker 继续提交。

> 第一版中的“调度”指任务分发、租约接管和失败重试。当前范围不包含 cron 定时任务、DAG 编排、多租户或账号体系。项目没有可据以宣称生产级吞吐量的压测结果。

## 技术与结构

| 部分 | 技术／职责 |
|---|---|
| 后端 | Java 21、Spring Boot、Spring JDBC、Flyway、Commons CSV |
| 前端 | Vue 3、TypeScript、Vite，构建产物由 API 服务提供 |
| 数据库 | PostgreSQL 17；持久化上传元信息、任务、检查点、行错误与商品 |
| 本地队列 | PostgreSQL 任务领取、租约及后台恢复 |
| RabbitMQ 档 | 部署档使用 RabbitMQ 唤醒 Worker，并通过 Outbox 投递任务通知；与本地档共用处理引擎 |

```text
浏览器 :18090
    │ 上传／查询
    ▼
API 进程 :18090 ───────► PostgreSQL :15439
    │                          ▲
    │ 共享上传文件              │ 领取任务、分批提交、检查点
    ▼                          │
.runtime/storage ◄────── Worker 进程 :18091
```

```text
backend/                  Java API、Worker、迁移及测试
web/                      Vue 工作台
scripts/                  本地构建、进程管理、数据库及样本生成
deploy/                   Dockerfile 与 Compose 部署档
docs/API-CONTRACT.md      前后端接口和业务规则
docs/IMPLEMENTATION-PLAN.md  第一版范围与验收方案
docs/DEMO-RUNBOOK.md       现场演示和故障恢复步骤
docs/VERIFICATION.md       实际验证结果（以该文件中的证据为准）
.runtime/                 本机数据库、上传文件、日志和 PID；不入版本控制
```

## 本机启动（Windows）

需要 JDK 21、Maven 3.9+、Node.js 24、pnpm 11.25.0，以及 PostgreSQL 17 原生工具。

本机已发现可复用的便携工具位于相邻 `yijia-local-runtime/tools`，脚本只复用工具二进制；本项目数据、端口与进程独立，**不会连接或停止其他项目服务**。在其他电脑上可设置以下进程级环境变量，或者将工具解压到本项目 `.tools`：

```powershell
$env:TASKFLOW_JAVA_HOME = 'C:\tools\jdk-21'
$env:TASKFLOW_MAVEN_HOME = 'C:\tools\apache-maven-3.9.11'
$env:TASKFLOW_POSTGRES_BIN = 'C:\tools\pgsql\bin'
```

在项目目录执行：

```powershell
.\scripts\check-env.ps1
.\scripts\Start-Local.ps1
```

也可双击根目录的 `启动本地.cmd`。首次启动会创建本项目数据库，安装前端依赖、构建前端、运行后端测试并打包，再启动独立 API 和 Worker 隐藏进程。构建失败会停止后续启动，不忽略测试失败。

- 工作台：`http://127.0.0.1:18090`
- API 健康检查：`http://127.0.0.1:18090/api/health`
- Worker 健康检查：`http://127.0.0.1:18091/api/health`
- PostgreSQL：`jdbc:postgresql://127.0.0.1:15439/batchflow`
- 独立测试库：`batchflow_test`，测试不会连接业务库。

本地演示数据库只监听 `127.0.0.1`，用户 `batchflow`，使用 trust 认证、密码为空。该档仅适合本机演示，不应改为公网监听。

```powershell
# 查看进程和健康状态。
.\scripts\Check-Local.ps1

# 停止 API、Worker，保留数据库和全部数据。
.\scripts\Stop-Local.ps1

# 同时停止本项目数据库；仍保留数据。
.\scripts\Stop-Local.ps1 -StopDatabase

# 已构建过 jar 时，只启动缺失进程，不重复构建。
.\scripts\Start-Local.ps1 -SkipBuild

# 为故障演示放慢处理速度：每批提交后等待 1000 ms。
.\scripts\Restart-Worker.ps1 -BatchDelayMs 1000
```

脚本会核对 PID 记录、jar 绝对路径、角色与进程创建时间。发现端口被其他进程占用时会报错，不会结束未知进程。应用日志位于 `.runtime/logs/api.out.log`、`worker.out.log` 及对应 `.err.log`。

如果要修改源代码后重新打包，请先停止 API／Worker，再运行不带 `-SkipBuild` 的启动命令。

## 开发与测试

```powershell
.\scripts\Start-Database.ps1
.\scripts\mvn.ps1 -f backend/pom.xml test

cd web
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm dev --host 127.0.0.1 --port 5179
```

前端开发服务器需通过 Vite 代理连接 `127.0.0.1:18090`。`web/dist` 会由 Maven 作为静态资源打进 `backend/target/batchflow-0.1.0.jar`。

真实 PostgreSQL 集成测试使用独立数据库，可覆盖环境变量：

```powershell
$env:BATCHFLOW_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:15439/batchflow_test'
$env:BATCHFLOW_TEST_DB_USER = 'batchflow'
$env:BATCHFLOW_TEST_DB_PASSWORD = ''
.\scripts\mvn.ps1 -f backend/pom.xml verify
```

`.github/workflows/ci.yml` 配置了独立 PostgreSQL service、前端测试／类型检查／构建，以及 Maven 测试与打包；只有实际运行后的结果才构成 CI 通过证据。

## 故障恢复演示

工作台可下载示例 CSV。需要较长的处理过程时，可生成唯一 SKU 的合成数据：

```powershell
node scripts/generate-sample.mjs --rows 20000 --output .runtime/samples/recovery.csv
.\scripts\Restart-Worker.ps1 -BatchDelayMs 1000
```

样本生成器采用流式写入，已有输出文件会拒绝覆盖。`--invalid-every 25` 可让每 25 条记录生成一条非法价格，输出会显示预期成功／失败行数。合成样本不是生产数据或性能结论。

上传后设置批大小 200，观察已处理行数增加；按 [演示手册](docs/DEMO-RUNBOOK.md) 强制停止本项目 Worker，再重新启动。最终检查行数不变量、检查点与商品唯一性，而不仅仅检查页面显示“完成”。

## Docker Compose 部署档

```powershell
# 本地原生进程和数据库占用相同端口，先停止它们。
.\scripts\Stop-Local.ps1 -StopDatabase
docker compose -f deploy/compose.yaml up --build -d
docker compose -f deploy/compose.yaml ps
```

Compose 包含 PostgreSQL、RabbitMQ、API、Worker，上传文件通过命名卷共享。宿主机暴露的端口绑定 `127.0.0.1`，示例数据库／RabbitMQ 密码可以通过 `BATCHFLOW_DB_PASSWORD`、`BATCHFLOW_RABBIT_PASSWORD` 覆盖。RabbitMQ 管理页端口是 `15672`。

**`app.queue-mode=rabbitmq` 队列档已通过便携版 Erlang + RabbitMQ 4.4.1 完成本机实机验收（见 [实际验证记录](docs/VERIFICATION.md) 与 `scripts/Verify-RabbitMQ.ps1`）；由于本机为 Windows 11 家庭版且当前终端无管理员提权/未启用 WSL2 重启，Docker Desktop 与 `deploy/compose.yaml` 容器编排尚未在本机实机启动。** Docker 镜像构建阶段只打包 Java 产物；真实数据库集成测试由前述本地命令和 CI 执行，不能以镜像构建成功替代测试。

## 项目说明的证据边界

- 以 [实际验证记录](docs/VERIFICATION.md) 区分已验证、未覆盖与待处理项目。
- 当前处理业务是商品 CSV，上传限制、字段规则和状态以 [接口契约](docs/API-CONTRACT.md) 为准。
- 不宣称消息 exactly-once 投递；是否重复提交数据，依靠数据库幂等与租约 fencing 约束验证。
- 不将单机故障演示写成生产高可用，也不虚构 QPS、吞吐量或延迟优化百分比。
- 暂不包含账号权限、公开互联网部署、对象存储、文件生命周期清理、cron 与 DAG 编排。
