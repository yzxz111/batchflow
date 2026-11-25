# 官方技术资料与允许使用的模式

核对日期：2026-11-25。下列网页和 Maven Central 元数据由本轮文档发现子任务实际读取；这里整理其 API 和适用边界。版本来自官方仓库可用工件，不以滚动官网上的 SNAPSHOT 版本作为依赖。

## 固定依赖

- Spring Boot `3.5.16`。BOM：<https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom>。
- 上述 BOM 管理 Spring Framework `6.2.19`、Spring AMQP `3.2.12`、PostgreSQL JDBC `42.7.11`。构建时以实际依赖树为准。
- Commons CSV `1.14.1`。官网滚动 Javadoc 中出现的 `1.14.2-SNAPSHOT` 不能直接作为本项目正式版本。
- JDK 21。第一版本地运行优先使用本机已存在的便携工具，不修改全局 Java 环境。

## Spring JDBC 与事务

来源：

- <https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html>：Querying、Updating、Retrieving Auto-generated Keys。
- <https://docs.spring.io/spring-framework/docs/6.2.9/javadoc-api/org/springframework/jdbc/core/JdbcTemplate.html>：核对重载签名。实施版本随 Boot BOM，编译与集成测试再次验证。
- <https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html>：TransactionTemplate。

已核对模式：

```java
List<T> query(String sql, RowMapper<T> mapper, Object... args);
T queryForObject(String sql, Class<T> type, Object... args);
int update(PreparedStatementCreator creator, KeyHolder keyHolder);
int[] batchUpdate(String sql, List<Object[]> batchArgs);

TransactionTemplate tx = new TransactionTemplate(transactionManager);
tx.execute(status -> { /* 同一批次的所有持久化操作 */ return result; });
```

SQL 参数必须绑定，不能拼接来自请求的过滤值；排序列需要服务端枚举。主键可用应用生成 UUID；若用数据库生成键，采用 `GeneratedKeyHolder` 和 `prepareStatement(sql, new String[]{"id"})`。

## CSV 与上传

来源：

- <https://commons.apache.org/proper/commons-csv/apidocs/index.html>：Using Headers、Handling Byte Order Marks、迭代读取。
- <https://docs.spring.io/spring-framework/docs/6.2.9/javadoc-api/org/springframework/web/multipart/MultipartFile.html>：getInputStream、getSize、isEmpty、transferTo(Path)。

已核对模式：

```java
try (Reader reader = new InputStreamReader(
        BOMInputStream.builder().setPath(path).get(), StandardCharsets.UTF_8);
     CSVParser parser = CSVFormat.EXCEL.builder().setHeader().get().parse(reader)) {
    for (CSVRecord record : parser) { /* 逐条处理 */ }
}
```

`BOMInputStream` 属于 Commons IO。实际实现应对非法 UTF-8 使用 REPORT，限制上传大小/行数/字段长度，不能用 `getRecords()` 收集全量。原始文件名仅用于展示，存储路径采用服务器生成的 ID。错误导出还要处理以 `= + - @` 等字符开头的用户内容，避免电子表格把它当公式。

## PostgreSQL 队列与一致性

来源：<https://www.postgresql.org/docs/17/sql-select.html>，锁定子句、SKIP LOCKED。

`FOR UPDATE SKIP LOCKED` 可用于领取队列表记录，不用于需要完整一致视图的统计。Worker 处理结果与检查点必须同事务提交；仅靠消息确认不足以证明数据库业务完成。租约、递增 token 和事务内条件检查必须通过旧 Worker 失效测试。

PostgreSQL 事务里出现唯一约束错误后不能捕获并继续把该事务当正常使用。重复 SKU 可用 `ON CONFLICT DO NOTHING` 并检查影响行数，再记录行错误。第一版使用真实独立 PostgreSQL，避免把 H2 不同方言的表现当作 PostgreSQL 的证据。

## RabbitMQ 投递

来源：

- <https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html>：`@RabbitListener(queues = "...")`。
- <https://docs.spring.io/spring-amqp/reference/amqp/template.html>：Publisher Confirms and Returns。

已核对模式：

```java
CorrelationData cd = new CorrelationData(messageId);
rabbitTemplate.convertAndSend(exchange, routingKey, jobId, cd);
boolean ack = cd.getFuture().get(10, TimeUnit.SECONDS).isAck();
ReturnedMessage returned = cd.getReturn();
```

应配置 correlated confirms、publisher returns 和 mandatory；只有 ack 且未 return 才能将 Outbox 标记为已投递。确认后更新数据库前进程退出可能导致重发，处理引擎仍须幂等。同步 listener 必须在实际处理成功/可靠交接后返回，不能仅丢给内存线程就声称可靠完成。

本机未发现 Docker，RabbitMQ 部署档需要真实 broker 集成验收；不得把数据库队列的本机验证写成 RabbitMQ 已通过。
