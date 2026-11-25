# GitHub Actions 与 Docker Compose 实机验收

- 私有仓库：`yzxz111/batchflow`。
- 运行：[45583013308](https://github.com/yzxz111/batchflow/actions/runs/45583013308)，第 1 次运行／第 1 次尝试，结论 `success`。
- 受测提交：`4f908cab0ad9320d684ec15e136726aff8942987`。
- 北京时间 2026-11-25 17:11:18 启动；两个 job 均成功，最后一个 job 于 17:16:08 结束，运行元数据于 17:16:09 更新。
- 前端 87/87；后端 33/33，失败、错误、跳过均为 0；类型检查与构建通过。
- Compose 验收 9/9；1200 行任务在检查点 100 强停并从 100 接管，最终成功 1152、错误 48、attempt 2，商品数 1152。
- 数据计数异常、重复 SKU、Outbox 待投递、队列消息数均为 0；本轮隔离容器、数据卷和镜像清理成功。

`run.json`、`jobs.json`、`artifacts.json` 为 GitHub API 回读元数据；`summary.json` 为下载报告后逐项断言生成的摘要。两份 ZIP 是 GitHub 原始 artifacts，SHA-256 与 GitHub 返回的 digest 逐一一致，解包文件与原始 ZIP 逐字节匹配。`manifest.json` 覆盖本目录除清单本身外的所有文件。

GitHub artifacts 仅保留 14 天，本目录保存了独立副本。这里验证的是 GitHub Linux runner 上的 PostgreSQL 17 + RabbitMQ 4.1-management + API/Worker 隔离容器。原 Windows 本机缺少 Docker CLI 的历史 `BLOCKED` 报告保留；本次没有安装本机 Docker，也没有进行公网生产部署。
