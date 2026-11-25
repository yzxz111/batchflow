# BatchFlow 本地演示手册

本手册用于演示真实 API、数据库和独立 Worker，不使用前端假任务或伪造进度。调度范围为任务领取、分批执行、失败重试及租约到期接管。

## 1. 启动并解释架构

```powershell
.\scripts\Start-Local.ps1
.\scripts\Check-Local.ps1
```

打开 `http://127.0.0.1:18090`。API 进程负责上传和查询，Worker 进程领取任务，两者共享本项目 PostgreSQL 和上传目录。首次没有任务时应显示真实空态。

本地为数据库队列档。不要将本地档演示称为 RabbitMQ 验收。

## 2. 正常导入和错误行

1. 从工作台下载商品 CSV 示例。
2. 上传文件，检查表头、预览与字段映射。
3. 提交任务，查看进度、成功数、错误数与任务事件。
4. 打开错误明细并下载错误 CSV；查看商品列表。
5. 同样 SKU 再次导入时应出现重复 SKU 行错误，不覆盖此前商品。

如果需要可预测的错误行数：

```powershell
node scripts/generate-sample.mjs --rows 1000 --invalid-every 25 --output .runtime/samples/validation-demo.csv
```

该文件预期 960 条合法数据与 40 条非法价格；每次生成使用独立 SKU 前缀。预期值是样本构造结果，仍应与实际任务结果核对。

## 3. Worker 强停与恢复

生成更长的样本并放慢批次：

```powershell
node scripts/generate-sample.mjs --rows 20000 --output .runtime/samples/recovery-demo.csv
.\scripts\Restart-Worker.ps1 -BatchDelayMs 1000
```

上传该文件，批大小设为 200。等待任务进入 RUNNING 且 processedRows 大于 0、小于 totalRows，然后执行：

```powershell
# 复用脚本内的 PID、jar、role 和启动时间校验，绝不按进程名批量结束 Java。
. .\scripts\env.ps1
. .\scripts\local-processes.ps1
Stop-TaskflowProcess -Role worker
```

此时 API 保持运行，任务的已提交检查点仍能查询。等待片刻查看 Worker 心跳变化；任务不会仅因 Worker 停止而立即宣称完成。

重新启动 Worker：

```powershell
.\scripts\Restart-Worker.ps1 -BatchDelayMs 1000
```

旧租约过期后，新 Worker 应从已提交检查点接管。检查恢复前后的 processedRows、checkpoint、attempt、事件及最终结果。恢复不意味着撤销已提交批次，也不应重新插入它们。

完成后还原演示速度：

```powershell
.\scripts\Restart-Worker.ps1 -BatchDelayMs 200
```

## 4. 数据正确性核对

对每个任务核对：

```text
processedRows = successRows + errorRows
checkpoint = processedRows
processedRows <= totalRows
```

对于全部合法、SKU 唯一的恢复样本，最终应为 COMPLETED、successRows 等于样本行数、errorRows 为 0。商品表中应没有重复 SKU。

如需直接检查数据库：

```powershell
. .\scripts\env.ps1
& (Join-Path $TaskflowPostgresBin 'psql.exe') -h 127.0.0.1 -p 15439 -U batchflow -d batchflow
```

表名与字段以 `backend/src/main/resources/db/migration` 中实际迁移为准；不要将未知 SQL 示例作为验收结果。

## 5. 取消和重试

- 在较长任务运行时取消，确认状态变成 CANCELLED，后续批次不再提交。
- FAILED 任务可通过接口或工作台重试；保留已提交检查点。业务校验产生的错误行属于 COMPLETED_WITH_ERRORS，不应把它们当成基础设施失败任务直接重试。
- 不通过修改数据库或伪造页面状态制造“成功恢复”。需要专门验证失败注入时，使用测试所提供的场景并保留命令及结果。

## 6. 演示结束

```powershell
.\scripts\Stop-Local.ps1 -StopDatabase
```

数据保留在 `.runtime`。不要为了清理演示记录直接删除整个工作区；如果要新建演示数据，生成新的 SKU 前缀即可。

## 可以说明与暂不能说明的内容

可以结合源代码解释事务边界、检查点、租约、fencing、幂等、异常分类和 Outbox 设计。能声称已验证的内容，以 `VERIFICATION.md` 的实际证据为准。

未完成实机验证前，不宣称 RabbitMQ 投递、Docker Compose、跨机器恢复或生产高并发已通过。当前没有 cron 或 DAG 功能。
