# BatchFlow 第一版本地交付与复验

## 交付范围

第一版实现商品 CSV 上传、预览与字段映射、后台分批导入、错误明细与 CSV 导出、商品查询、取消与失败重试。API 和 Worker 独立运行，共享 PostgreSQL 与上传目录；支持 `database` 和 `rabbitmq` 两种队列档。

“调度”范围是任务领取、失败重排和租约过期接管。当前没有 cron、DAG、多租户、账号权限或公网生产部署。单机样本结果不代表生产高可用或集群吞吐能力。

## 一次完成本地复验

先停止本项目 API/Worker，保留数据库与数据。不要关闭其他项目进程。

```powershell
.\scripts\Stop-Local.ps1
.\scripts\Verify-Local.ps1
```

完整复验需要 Java 21、Maven、项目锁定的 pnpm、Node 24、PostgreSQL，以及 Python 3.12/Playwright、本机 Chrome 或 Edge、便携 Erlang/RabbitMQ。工具位置可通过 `scripts/env.ps1` 支持的环境变量指定。仅启动数据库队列档演示时不需要浏览器自动化或 RabbitMQ。

`Verify-Local.ps1` 按顺序执行依赖锁校验、前端原始 JSON 测试报告、类型检查与构建、真实 PostgreSQL 后端测试与打包、浏览器交互、单机性能样本、真实 RabbitMQ 投递及 Worker 强停恢复。结果写入新的 `.runtime/evidence/local/<run-id>/`，不会覆盖以前的验收目录。失败会中断后续步骤并保留失败报告；本轮启动的 API/Worker 会清理，数据库和历史数据保留。

```powershell
# 指定一个尚不存在的本次报告目录。
.\scripts\Verify-Local.ps1 -ReportDirectory .runtime/evidence/local/my-run

# 依赖已经按锁文件安装时，可以显式跳过安装；测试和构建仍执行。
.\scripts\Verify-Local.ps1 -SkipInstall

# Python 不在标准位置时显式指定。
.\scripts\Verify-Local.ps1 -PythonExecutable C:\tools\python312\python.exe
```

每个子报告记录自己的通过状态、时间、任务 ID 和验证边界。浏览器报告包含本轮截图及 Playwright trace；性能报告包含原始计时分项与计时定义；RabbitMQ 报告分别保存强停前观察值、停机后持久化值和恢复事件中的检查点。

## 固化验收证据

```powershell
.\scripts\Export-Evidence.ps1 -ReportDirectory .runtime/evidence/local/my-run
```

导出仅接受完整 `PASSED` 的本地报告，默认复制到 `docs/evidence/<run-id>/`，同时生成文件 SHA-256 清单和源码哈希。报告、截图、trace 可以随该目录保存和交付；不要直接复制整个 `.runtime` 或 `.tools`。数据库、上传文件、工具二进制和 Erlang cookie 不属于源码证据包。

验收时源码可能尚未形成最终提交。报告保留当时的 Git 基线和是否有工作区修改，`manifest.json` 中的源码哈希用于确定实际验证的文件。最终文档提交发生在测试之后，不应把提交时间当作测试执行时间。

Playwright trace 的本地查看命令：

```powershell
py -3.12 -m playwright show-trace docs/evidence/<run-id>/e2e/trace.zip
```

## 容器与远端 CI

```powershell
# 在已经具备 Docker Engine 与 Compose v2 的环境执行。
node scripts/verify-compose.mjs
```

该入口使用独立 Compose 项目和测试端口，构建镜像并验证真实 HTTP 导入、消息队列、Worker 恢复及数据不变量，再归档容器日志并清理本轮资源。具体结果以生成的 `report.json` 为准；缺少 Docker 时的环境受限报告不构成容器通过证据。

`.github/workflows/ci.yml` 分别配置前后端校验和隔离 Compose 冒烟任务，保留原始测试报告及容器证据。配置文件存在、本地校验成功都不能代替 GitHub Actions 的实际运行。关联用户指定的仓库后，推送分支或手动触发工作流，并保留运行地址及下载的 artifacts。

## 日常演示与数据

使用根目录 `启动本地.cmd` 或 `scripts/Start-Local.ps1 -SkipBuild` 启动已经打包的数据库队列档。查看 `docs/DEMO-RUNBOOK.md` 演示正常导入、错误明细和 Worker 恢复。

复验会在本项目业务库中新增带独立 SKU 前缀的合成任务和商品，这是测试数据，不是实际业务吞吐或正式业务记录。不要删除已有记录来制造空态或掩盖失败；需要空库演示时使用明确隔离的新环境。
