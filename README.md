# AI Resume RAG Agent

一个基于 Spring Boot 的智能简历解析与 RAG 问答项目。系统支持简历上传、批量解析、父子分片、向量化入库、混合检索、Tool Calling Agent、SSE 流式响应、查询 Trace 记录，并通过 Spring AI MCP Server 将简历 RAG 能力暴露为标准 MCP Tools。

## 项目定位

本项目面向招聘和人才筛选场景，目标是让用户可以用自然语言查询候选人简历，例如：

- 哪些候选人适合 Java 后端岗位？
- 对比几位候选人的 Spring Boot、Redis、微服务项目经验。
- 找出有架构设计、团队管理或高并发经验的候选人。
- 某位候选人的项目经历和技术栈有哪些证据？

系统不是简单的“文档向量化 + TopK + LLM 回答”，而是围绕简历筛选场景做了一套更工程化的 RAG 链路。

## 核心能力

- 多格式简历解析：支持 PDF、DOC、DOCX。
- 批量上传：支持多文件简历上传。
- 简历去重覆盖：同一候选人重复上传时可覆盖旧数据。
- 父子分片：子块负责精准召回，父块负责完整上下文回填。
- Metadata 提取：抽取技能、公司、学校、岗位、项目、行业等关键词。
- 混合检索：向量检索 + 关键词检索并行召回。
- RRF 重排：融合向量召回和关键词召回结果。
- 动态截断：根据重排分数断崖式下跌截断上下文，减少无效 Token。
- 横向对比：识别候选人对比意图，动态扩大召回并要求表格化输出。
- Tool Calling Agent：LLM 自主判断是否检索、调用哪个工具、调用多少次。
- SSE 流式响应：实时推送预处理、检索、重排、生成等状态。
- 查询 Trace：记录每一步耗时、参数、召回结果和降级信息。
- MCP Server：通过 Spring AI 暴露简历 RAG 工具给外部 MCP Client。

## 技术栈

- Java 17
- Spring Boot 4.0.5
- Spring WebMVC
- Spring AI 1.1.6
- Spring AI MCP Server WebMVC
- LangChain4j 1.13.x
- Apache Tika Document Parser
- Redis / Redis Vector Search
- MySQL
- MyBatis-Plus
- CompletableFuture / ThreadPoolTaskExecutor
- SSE

## 架构流程

### 简历入库链路

```text
用户上传简历
  -> 文件格式校验
  -> Apache Tika 解析 PDF/DOC/DOCX
  -> 判断是否为有效简历
  -> 抽取候选人信息和 metadata
  -> 父子分片
  -> 子块 embedding
  -> Redis 写入向量和 metadata
  -> MySQL 保存简历主表、父块内容、原文件路径
```

### 简历问答链路

```text
用户提问
  -> 三合一预处理
     - 意图识别
     - 查询重写
     - metadata 约束提取
  -> Tool Agent 判断是否调用简历工具
  -> 向量检索和关键词检索并行执行
  -> 父块聚合
  -> RRF 重排
  -> 断崖式动态截断
  -> LLM 基于证据生成答案
  -> SSE 返回状态、答案和 trace
```

## 为什么使用父子分片

普通固定长度分片容易把一段完整项目经历截断，导致 LLM 拿不到完整上下文。

本项目采用父子分片：

- 子块：粒度小，适合向量召回，提高命中精度。
- 父块：语义完整，适合回填给 LLM，保证回答证据完整。

简言之：**子块找得准，父块给得全。**

## 为什么使用混合检索

向量检索适合语义相似匹配，但对技术栈、学校、公司、人名等精确实体不一定稳定。

关键词检索适合精确匹配，但容易漏掉同义表达。

因此系统采用：

```text
向量检索：语义召回
关键词检索：精确实体召回
RRF 重排：融合两路结果
```

这样可以降低单一路径召回偏差，提高简历检索的准确率和覆盖率。

## MCP Tools

项目通过 Spring AI MCP Server 暴露 3 个工具：

| Tool | 说明 |
| --- | --- |
| `resume_rag_search` | 只检索简历 RAG 上下文，不生成最终答案，适合外部 Agent 自己组织回答。 |
| `resume_answer_question` | 执行完整简历问答，由服务端完成预处理、检索、Tool Agent 调用和答案生成。 |
| `resume_list_uploaded` | 列出已上传简历基础信息，方便外部 Agent 了解候选人池。 |

MCP SSE 地址：

```text
http://localhost:8080/mcp/sse
```

## 主要接口

### 上传单份简历

```http
POST /api/documents/upload-resume
Content-Type: multipart/form-data
```

字段：

- `file`：PDF、DOC 或 DOCX 简历文件。

### 批量上传简历

```http
POST /api/documents/upload-resumes
Content-Type: multipart/form-data
```

字段：

- `files`：多份简历文件。

### 简历问答，LangChain4j Tool Agent 版本

```http
GET /api/documents/query-resume?userId=default-user&query=哪些候选人适合 Java 后端岗位
Accept: text/event-stream
```

SSE 事件：

- `status`：当前处理状态。
- `token`：答案内容。
- `trace`：查询链路 Trace。
- `done`：完成标记。
- `error`：错误信息。

### 简历问答，Spring AI Tool 版本

```http
GET /api/documents/query-resume-spring-ai?userId=default-user&query=对比 Java 候选人
Accept: text/event-stream
```

### 简历列表

```http
GET /api/documents/resumes
```

### 下载简历原文件

```http
GET /api/documents/resumes/{resumeId}/download?userId=default-user
```

### 删除简历

```http
DELETE /api/documents/resumes/{resumeId}?userId=default-user
```

## 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8+
- Redis，并启用 RediSearch / Vector Search 能力
- 可用的 DeepSeek/OpenAI-compatible API Key

## 配置说明

核心配置位于 `src/main/resources/application.properties`：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ai_project?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=root

spring.data.redis.host=localhost
spring.data.redis.port=6379

app.vector.index-name=talent-index-v4
app.vector.prefix=talent:v4:
app.resume.storage-dir=${user.home}/.ai_project/resumes

DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
GEMINI_AI_KEY=${GEMINI_AI_KEY}
```

建议通过环境变量配置 API Key：

```bash
export DEEPSEEK_API_KEY=your_key
export OPENAI_API_KEY=your_key
export GEMINI_AI_KEY=your_key
```

## 数据库初始化

项目包含 SQL 迁移脚本：

```text
src/main/resources/sql/
  chat_memory_category_migration.sql
  resume_batch_migration.sql
  resume_dedup_migration.sql
  resume_query_trace_migration.sql
```

启动前请根据实际数据库情况执行对应建表和迁移脚本。

## 启动项目

```bash
mvn -q -DskipTests compile
mvn spring-boot:run
```

默认服务地址：

```text
http://localhost:8080
```

## MCP 测试

### 使用 MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

选择 SSE，填入：

```text
http://localhost:8080/mcp/sse
```

连接成功后可以查看并调用：

- `resume_rag_search`
- `resume_answer_question`
- `resume_list_uploaded`

### 使用 curl 简单测试 SSE

```bash
curl -N http://localhost:8080/mcp/sse
```

正常情况下会建立 SSE 连接，并返回后续 message endpoint。

## 查询 Trace

每次简历问答会记录关键步骤：

- 查询预处理耗时和结果
- intent
- rewrittenQuery
- metadata constraints
- 向量检索耗时和返回数量
- 关键词检索耗时和返回数量
- 重排和动态截断信息
- 最终上下文数量
- 最终回答耗时
- 降级和异常信息

Trace 用于：

- 定位响应慢在哪一步
- 分析检索结果为什么不准
- 判断 query rewrite 是否偏移
- 调整召回数量、权重和截断阈值
- 排查 Tool 调用失败或降级

## 项目亮点

### 1. 工程化 RAG 链路

不是简单 TopK 向量检索，而是包含：

- 查询预处理
- 父子分片
- 混合召回
- RRF 重排
- 动态截断
- 证据约束输出

### 2. Tool Calling Agent

将简历检索和简历列表封装为 Tool，LLM 可以根据问题自主决定：

- 是否检索
- 调用哪个工具
- 调用多少次
- 每次检索多少上下文

### 3. MCP 对外暴露

通过 Spring AI MCP Server 将内部 RAG 能力暴露为 MCP Tools，使外部 MCP Client 可以标准化调用简历检索能力。

### 4. 可观测性

通过 SSE 和 Trace 记录，让长链路 RAG 查询具备实时反馈和可排查能力。

### 5. 降级机制

- 预处理 JSON 解析失败时，用正则提取 intent 和 rewrittenQuery。
- rewrittenQuery 缺失时，回退到用户原始 query。
- 向量检索或关键词检索单路失败时，另一条链路仍可继续。
- metadata 约束过严时，支持放宽条件重新检索。
- Tool 外层异常时返回可理解的降级信息，不直接中断 Agent。

## 后续优化方向

- 引入更完整的任务状态机，保证 MySQL、Redis 向量索引和文件存储的最终一致性。
- 引入 Micrometer + Prometheus + Grafana 做全链路监控。
- 增加答案证据校验器，降低 LLM 幻觉。
- 对 query embedding、预处理结果和高频检索结果做缓存。
- 对 MCP 调用增加鉴权，避免模型传入不可信 userId。
- 增加离线评估集，评估 Recall@K、Precision@K、MRR、答案证据覆盖率。
- 支持 OCR，增强扫描版 PDF 简历解析能力。

## 目录结构

```text
src/main/java/com/ai/project/ai_project
  config/        配置类，包含 LangChain4j、Spring AI MCP、线程池、MyBatis 等
  controller/    HTTP 和 SSE 接口
  domain/        MySQL 实体
  mapper/        MyBatis-Plus Mapper
  service/       简历解析、RAG、Agent、MCP Tool、Trace 等核心业务
  service/dto/   接口和业务 DTO
  util/          文本清洗、意图路由、MemoryId 等工具类
```

## 注意事项

- 当前默认用户为 `default-user`，生产环境需要接入认证体系。
- MCP Tool 中的 `userId` 不应在生产环境中完全信任，应由服务端认证上下文决定。
- MySQL 事务不能覆盖 Redis 和本地文件，生产环境建议引入任务状态机和补偿机制。
- 扫描版 PDF 需要 OCR 支持，当前主要面向可提取文本的简历文件。
