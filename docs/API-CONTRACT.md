# BatchFlow 第一版接口契约

本文件是前后端共同实现的接口，日期为 2026-11-25。业务场景是商品 CSV 批量导入，不包含账号、真实支付和多租户。所有时间使用 ISO 8601 UTC，界面按本地时区显示。金额使用 JSON number，后端使用 BigDecimal；计数使用整数。API 前缀为 `/api`。

## 统一约定

- 成功直接返回对象，不再套 data 包装。错误返回 `{ "message": "用户可读错误", "code": "INVALID_REQUEST" }`，使用真实 HTTP 状态。
- 分页结构为 `{ "items": [], "total": 0, "page": 0, "size": 20 }`；page 从 0 开始，size 最大 100。
- 状态枚举：`QUEUED`、`RUNNING`、`COMPLETED`、`COMPLETED_WITH_ERRORS`、`FAILED`、`CANCELLED`。
- 所有界面数据来自真实 API；初始没有任务时显示空态，不预填虚构吞吐量、可用率或任务记录。

## 上传、预览与模板

`POST /uploads`：multipart/form-data，字段 `file`，UTF-8 CSV，支持 BOM、带引号字段及字段内换行。限制 50 MiB、最多 1,000,000 行，拒绝空文件与重复/空表头。

响应：`{ "uploadId": "uuid", "filename": "products.csv", "headers": ["sku","name","category","price","stock"], "previewRows": [{"sku":"P001","name":"商品","category":"办公","price":"12.50","stock":"10"}], "totalRows": 1000 }`。

`GET /templates/products.csv`：下载一个包含合法与非法数据的示例 CSV，真实文件内容；页面可下载后自行导入，也可使用同样内容构造 File 走上传与创建任务接口。

## 创建与查询任务

`POST /tasks`，请求头 `Idempotency-Key: uuid`（推荐发送）；同一个 key 与相同语义请求返回原任务，key 对应不同请求返回 409。

```json
{
  "name": "11月商品导入",
  "uploadId": "uuid",
  "mapping": {"sku":"sku","name":"name","category":"category","price":"price","stock":"stock"},
  "batchSize": 200,
  "maxAttempts": 3
}
```

sku/name/price/stock 映射必填，category 可省略；batchSize 为 10~1000，maxAttempts 为 1~5。响应为 Task 对象，HTTP 201（重放可为 200）。

Task：`{ "id":"uuid", "name":"...", "filename":"...", "status":"QUEUED", "totalRows":1000, "processedRows":0, "successRows":0, "errorRows":0, "progress":0, "batchSize":200, "attempt":0, "maxAttempts":3, "checkpoint":0, "workerId":null, "createdAt":"...", "startedAt":null, "finishedAt":null, "lastHeartbeatAt":null, "lastError":null }`。

progress 是 0~100 的数值，checkpoint 是已持久化的 CSV 逻辑记录序号（不含表头）。始终满足 processedRows = successRows + errorRows，checkpoint = processedRows，processedRows <= totalRows。

- `GET /tasks?q=&status=&page=0&size=20`：Task 分页；默认创建时间降序。
- `GET /tasks/{id}`：`{ ...Task, "events": [{"id":1,"type":"CREATED","message":"任务已创建","createdAt":"..."}] }`；事件按时间正序，限制最新 200 条。
- `POST /tasks/{id}/retry`：只允许 FAILED；保留已提交检查点和行结果，重置本轮 attempt，重排任务；返回 Task。
- `POST /tasks/{id}/cancel`：只允许 QUEUED/RUNNING；返回 Task。后端必须阻止取消后旧 Worker 再提交数据。
- `GET /tasks/{id}/errors?page=0&size=20`：`{ "items":[{"rowNumber":3,"sku":"P003","reason":"价格必须大于 0","rawData":"..."}], "total":1,"page":0,"size":20 }`。rowNumber 从 2 开始，按 CSV 逻辑记录计算，表头为第 1 条。
- `GET /tasks/{id}/errors.csv`：下载带 BOM 的 UTF-8 错误明细，防止用户输入触发电子表格公式。

## 总览与商品

`GET /overview`：`{ "totalTasks":0, "runningTasks":0, "queuedTasks":0, "completedTasks":0, "failedTasks":0, "cancelledTasks":0, "processedRows":0, "successRows":0, "errorRows":0, "productCount":0, "queueMode":"database", "workers":[{"id":"worker-...","status":"ONLINE","lastSeenAt":"...","currentTaskId":null}] }`。

completedTasks 包含 COMPLETED_WITH_ERRORS。worker 状态 ONLINE/OFFLINE 根据真实心跳判断。queueMode 是 database 或 rabbitmq。

`GET /products?q=&page=0&size=20`：`{ "items":[{"id":"uuid","sku":"P001","name":"商品","category":"办公","price":12.5,"stock":10,"sourceTaskId":"uuid","createdAt":"..."}],"total":1,"page":0,"size":20 }`。

`GET /health`：`{ "status":"UP", "role":"api", "queueMode":"database" }`。数据库异常不得假报 UP。

## 业务规则

sku 长度 1~64，仅 ASCII 字母、数字、点、横线、下划线；name 非空且 <=200 字符；category <=100 字符；price >0，至多两位小数，且 <=99999999.99；stock 为 0~2147483647 整数。sku 全局唯一，文件内或跨任务重复均作为行错误，不覆盖旧商品。

## 运行约定

- 后端单 Maven 项目 `backend/`，Java 21，包名 `com.batchflow`，jar `batchflow-0.1.0.jar`。
- Vue 3 + TypeScript + Vite 位于 `web/`，构建输出 `web/dist/`。
- API 本地端口 18090，Worker 18091。`app.role=api|worker`，`app.queue-mode=database|rabbitmq`。
- PostgreSQL 本地独立端口 15439，库和用户 `batchflow`，数据 `.runtime/postgres/`，不使用其他项目数据库。
- 应用数据目录 `.runtime/storage/` 由 `app.storage-dir` 控制，API/Worker 必须共享。
- 数据库队列模式是真实开发运行模式；RabbitMQ 为部署档。二者共用处理引擎、数据库检查点和租约，不把本地档验证写成 RabbitMQ 已验证。
