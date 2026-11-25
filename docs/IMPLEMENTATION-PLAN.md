# BatchFlow 第一版实施方案

## Primary goal

用户可以上传商品 CSV、配置字段映射并提交后台导入，在界面看到真实进度和错误明细。Worker 在处理中途退出后，重启可从已提交批次恢复，已完成数据不重复入库；交付可以本机运行、验证和讲解的简历项目。

## Phase 0：文档与环境确认

**How this serves the primary goal:** 先确认真实可用 API 和本机工具，避免把未经验证的调用或依赖写入项目。

来源：Spring JDBC/TransactionTemplate、Spring AMQP、Commons CSV、Vue/Vite 官方文档。已发现可复用的便携 JDK21、Maven3.9、PostgreSQL 工具，数据和进程必须属于本项目。接口以 `API-CONTRACT.md` 为准。

验收：工具版本实际执行通过；文档发现记录 exact APIs 与版本；没有读取旧项目凭据或复用旧数据库。

## Phase 1：核心实现

**How this serves the primary goal:** 建立从上传到后台入库的完整链路，并把每批次提交与进度更新放进同一数据库事务。

- 后端：Spring Boot MVC、JdbcTemplate、Flyway、Commons CSV；上传预览、字段映射、任务状态机、商品校验、错误导出；独立 Worker、租约与 fencing token、检查点和重试；本地数据库队列，RabbitMQ 档通过 Outbox 投递并使用 publisher confirms。
- 前端：Vue3/TypeScript；中文任务工作台、总览、导入向导、任务详情、错误明细、商品列表；真实空态、加载态和错误态。
- 运行：本项目专用 PostgreSQL、本地启动/停止/检查脚本，以及 Docker Compose 部署档。

文档模式：JdbcTemplate 参数化 SQL；TransactionTemplate.execute；CSVFormat builder + parser；RabbitTemplate 确认后更新 Outbox。详细 API 来源整理到 `docs/TECHNICAL-REFERENCES.md`。

验收：后端测试与打包、前端类型检查与构建通过；真实 PostgreSQL 链路完成；不得用吞掉异常、忽略测试失败或内存假任务替代。

## Phase 2：复核与故障演示

**How this serves the primary goal:** 用可复现证据证明任务恢复和数据正确性，使项目能实际演示和回答面试追问。

检查：正常/异常 CSV、BOM/引号/换行、非法字段映射、重复 SKU、幂等键重放、取消和重试、Worker 中途退出后恢复；提交后检查进度不变量与商品唯一性。检查旧租约不能提交。浏览器检查上传向导、详情、错误导出、空态与布局。

反模式：不声称 exactly-once 消息投递；不依赖只存在于内存的任务状态；不在前端伪造进度；不把单机测试写成生产高并发；不把未运行的 RabbitMQ 档写成已验证；不提交或推送用户未指定的远程仓库。

最终记录实际命令、通过数量、样本规模、截图和未验证项，写入 `docs/VERIFICATION.md`。README 提供一键启动、故障演示步骤、设计取舍和后续扩展边界。
