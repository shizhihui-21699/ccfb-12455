# 同意管理平台（Consent Ledger）

按**用途**记录用户同意与撤回，每条记录锁定到**政策的具体版本**，并向业务系统提供毫秒级决策接口。面向"隐私政策改了好几版，用户当时到底同意了哪一版、哪些用途"这类监管核查场景。

```
                   ┌──────────────────────────────────────────┐
  用户 / 业务前端  │  管理后台（Thymeleaf）                    │
  ───────────────▶ │  政策版本 · 用户档案 · 事件账本 · 审计     │
                   └──────────────────────────────────────────┘
                                      │
  业务系统 ──X-API-Key──▶  /api/v1/decisions  （能否使用？）
           ──X-API-Key──▶  /api/v1/consents   （记录同意/撤回）
           ──X-API-Key──▶  /api/v1/subjects/{id}/receipt（导出凭证）

  写入：subject 链头行锁 → 追加事件（HMAC 链）→ 更新状态投影 → 失效缓存
  读取：政策目录（内存快照） + 状态事实（Caffeine）→ 纯函数判定
```

## 快速开始

```bash
# 需要 JDK 17+ 与 Maven 3.9+
mvn spring-boot:run
```

启动后：

| 入口 | 地址 | 凭据 |
| --- | --- | --- |
| 管理后台 | http://localhost:8080/admin | `admin` / `admin123!` |
| OpenAPI | http://localhost:8080/swagger-ui.html | — |
| 健康检查 | http://localhost:8080/actuator/health | — |

演示数据自动生成（可用 `--consent.demo-data.enabled=false` 关闭）：
两套政策、四个版本、约 2500 名用户、6600 余条事件。其中隐私政策经历
`v1.0 → v1.1（非实质性变更）→ v2.0（实质性变更，需重新同意）`，
用户按各自同意时所处的版本分成三批，可直接观察"旧版同意被判失效"的效果。

演示接入密钥：`demo-key-7f3a9c1e5b2d4f60`（生产用 `CONSENT_DEMO_API_KEY` 覆盖）。

## 设计要点

### 1. 同意记录永远指向一个不可变的政策版本

`PolicyVersion` 发布后内容不可修改。每次写入同意时，记录里冗余保存：

- `policyVersionId` / `policyVersionLabel` —— 哪一版
- `policyContentHash` —— 该版本的 SHA-256 内容指纹（覆盖全文 + 全部用途定义）
- `displayedPurposes` —— 用户当时屏幕上实际看到的用途清单（JSON）

因此"用户当时同意的是哪一版、同意了哪些用途"是可举证的事实，而不依赖事后推断。

### 2. 版本实质性变更会让旧同意失效

`PolicyVersion.reconsentRequired` 标记本次变更是否实质。决策时若用户同意所依据的
版本 ≠ 当前生效版本，且当前版本标记为实质性变更，返回
`allowed=false, reason=POLICY_RECONSENT_REQUIRED`。非实质性变更则沿用旧同意，
响应里如实返回同意实际所依据的版本。业务系统据此区分"引导重新同意"与"直接放行"。

### 3. 只追加的事件账本 + 每主体哈希链

- `consent_event` 只插入，不更新、不删除。
- 每条记录的 `recordHash = HMAC-SHA256(secret, 规范化字段 + prevHash)`，
  `prevHash` 串起同一主体的全部事件，链头存于 `consent_chain`。
- 写入时对链头 `SELECT ... FOR UPDATE`，同主体并发写入被串行化，不会分叉。
- 凭证导出时逐条复算，返回 `chainValid` 与失败明细。

链的顺序定义是**写入顺序（自增 id）**；`occurredAt` 是业务时间，历史数据回填时
两者可能不一致，凭证中对此有明确说明。

### 4. 决策快路径

```
POST /api/v1/decisions
  ├─ 政策目录：内存不可变快照，只在全局修订号变化时重建（无 DB 往返）
  ├─ 状态事实：Caffeine 缓存 (subjectId, purpose)，未命中 1 次索引查询
  └─ 判定：纯函数（事实 + 当前政策），不缓存结论
```

**为什么不缓存结论**：政策发布后即便用户状态没变，"能不能用"也可能变。
只缓存事实、每次现算结论，政策切换即刻生效且无需遍历失效。

**新鲜度**：

| 场景 | 生效延迟 | 机制 |
| --- | --- | --- |
| 本实例写入/撤回 | 立即 | 事务提交后精确失效对应 key |
| 其它实例写入/撤回 | ≤ 250ms + 一次查询 | 轮询全局 `cache_revision`，前进即清空本地缓存 |
| 政策发布/退役 | 立即（本实例）/ ≤250ms（跨实例） | 修订号推进 + 目录重建 |
| 保底 | 60s | 条目 TTL，避免任何遗漏导致永久陈旧 |

并发正确性：缓存加载与失效都通过 `Caffeine.asMap().compute()` 的同一把桶锁串行化，
失效必然排在旧加载之后，不会把旧值写回。同 key 加载单飞，热点失效瞬间的
上千并发只会产生一次数据库查询。

### 5. 用途的三种"不存在"

| 情况 | 决策接口 | 写入接口 |
| --- | --- | --- |
| 从未定义过（多半是拼错） | 422 `UNKNOWN_PURPOSE` | 422 `UNKNOWN_PURPOSE` |
| 曾定义、已从生效政策移除 | 200 `allowed=false, PURPOSE_RETIRED` | 422 `PURPOSE_RETIRED` |
| 依据非法定同意（履约/法定义务） | 200 `allowed=true, LEGAL_BASIS_CONTRACT` | 422 `NOT_CONSENT_BASED` |

这样业务系统不会因为运营改政策而硬崩，也不会把"履约所需"误记成"用户同意"。

## 接口

所有 `/api/v1/**` 需请求头 `X-API-Key`。错误统一为
`{code, message, timestamp, path, details}`，`code` 稳定可分支。

### 查询能否使用

```bash
curl -X POST http://localhost:8080/api/v1/decisions \
  -H 'X-API-Key: demo-key-7f3a9c1e5b2d4f60' -H 'Content-Type: application/json' \
  -d '{"subjectId":"U101800","purposes":["marketing_email","analytics_improvement"]}'
```

```json
{
  "subjectId": "U101800",
  "evaluatedAt": "2026-11-17T06:56:12Z",
  "cacheTtlSeconds": 60,
  "decisions": [
    {"allowed": true,  "reason": "CONSENT_GRANTED", "purposeCode": "marketing_email",
     "legalBasis": "CONSENT", "policyVersionId": 3, "policyVersionLabel": "v2.0",
     "reconsentRequired": false, "cached": true},
    {"allowed": false, "reason": "NO_CONSENT", "purposeCode": "analytics_improvement", "...": "..."}
  ]
}
```

`reason` 取值：`CONSENT_GRANTED`、`NO_CONSENT`、`SUBJECT_UNKNOWN`、
`POLICY_RECONSENT_REQUIRED`、`PURPOSE_RETIRED`、`LEGAL_BASIS_*`。

单用途便捷形式：`GET /api/v1/decisions/{subjectId}?purpose=marketing_email`。

### 记录同意 / 撤回

```bash
curl -X POST http://localhost:8080/api/v1/consents \
  -H 'X-API-Key: demo-key-7f3a9c1e5b2d4f60' -H 'Content-Type: application/json' \
  -d '{"subjectId":"U101800","displayName":"张三",
       "changes":[{"purposeCode":"marketing_email","action":"GRANT"},
                  {"purposeCode":"analytics_improvement","action":"WITHDRAW"}],
       "channel":"WEB","actor":"user"}'
```

同一显式页面上的多个勾选放在一次 `changes` 里，共享 `batchId`，
证明"用户是在同一屏一次性做出的选择"。整批校验失败则整批不写。

### 导出凭证

```bash
curl -H 'X-API-Key: demo-key-7f3a9c1e5b2d4f60' \
  http://localhost:8080/api/v1/subjects/U101800/receipt -o receipt.json
```

凭证内容：主体信息、各用途当前状态、按写入顺序的全部事件（含政策版本、
内容哈希、用户看到的用途清单、渠道/IP/UA、操作者）、链校验结果、整份凭证的
HMAC 签名。管理后台用户详情页也可一键下载，且会写入操作审计。

### 其它

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/subjects/{id}` | 该用户当前各用途状态 + 链头 |
| GET | `/api/v1/purposes` | 当前生效用途目录（含政策版本与内容哈希） |
| GET | `/admin/api/stats` | 运行指标（管理员会话） |

## 压测

```bash
node loadtest/loadtest.mjs --concurrency 200 --duration 30
```

本机（Apple Silicon / JDK 17 / H2 / 单实例）实测：

| 并发 | 吞吐 | p50 | p99 | 错误率 |
| --- | --- | --- | --- | --- |
| 100 | 27,458 req/s | 2.8 ms | 10.3 ms | 0 |
| 200 | 34,731 req/s | 3.1 ms | 15.1 ms | 0 |
| 400 | 27,227 req/s | 10.3 ms | 38.3 ms | 0 |

每请求含 4 个用途（即单用途 QPS 约 14 万），用户池 2000、缓存命中率 99.8%。
目标"高峰期每秒上千次"留有两个数量级余量。
生产部署换 PostgreSQL/MySQL 后，吞吐下限由状态缓存命中率决定；缓存未命中路径
只有一次 `(subject_id, purpose_code)` 唯一索引查询。

**跨实例一致性**（两个实例共享同一数据库，实测）：在 A 实例撤回后
约 600ms 内 B 实例的决策同步变为拒绝。上界由 `consent.revision-poll-interval`
（默认 250ms）决定。

## 数据模型

| 表 | 作用 |
| --- | --- |
| `policy` / `policy_version` / `policy_purpose` | 政策、不可变版本、版本内的用途快照 |
| `consent_subject` | 主体（最小识别信息） |
| `consent_event` | 只追加的同意/撤回事件，含政策版本快照与链哈希 |
| `consent_state` | (主体, 用途) 当前状态投影，决策只读这张表 |
| `consent_chain` | 每主体一条链头，写入时行锁串行化 |
| `cache_revision` | 全局单调修订号，跨实例缓存失效 |
| `api_client` / `admin_user` / `admin_audit_log` | 接入方、管理员、管理操作审计 |

`consent_state` 是纯派生数据，可随时从 `consent_event` 重算。

## 部署与安全

```bash
# 生产必须覆盖的配置
export CONSENT_RECEIPT_SECRET='<32+ 字节随机串>'
export CONSENT_ADMIN_PASSWORD='<强密码>'
export CONSENT_DEMO_API_KEY='<强随机密钥>'
export SPRING_DATASOURCE_URL='jdbc:postgresql://...'
export SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=...
mvn spring-boot:run -Dspring-boot.run.arguments=--consent.demo-data.enabled=false
```

- 管理员密码 BCrypt 存储；API 密钥只存 SHA-256 与可辨认前缀。
- 管理后台表单登录 + 会话 + CSRF（Cookie token）。
- 业务 API 无状态、密钥认证，可按接入方限定可访问用途（`consent.api-clients[].purposes`）。
- 凭证签名使用 HMAC；如需第三方独立验签，可将 `Hashing` 的签名实现替换为
  Ed25519/ECDSA 并在凭证里附带公钥指纹。
- 生产建议：`springdoc` 关闭、H2 控制台关闭（默认已关）、`management.endpoints`
  仅暴露 health、数据库定期备份并把 `consent_event` 归档到 WORM 存储。

## 测试

```bash
mvn test
```

20 个用例覆盖：同意→决策→撤回→决策全链路、批次校验、鉴权、政策目录、
凭证链与签名复算、同主体并发写入不分叉、缓存修订号失效。

## 代码结构

```
src/main/java/com/consentledger/
├── domain/     实体与枚举（政策版本、事件账本、状态投影、链头）
├── repo/       Spring Data 仓储
├── service/
│   ├── ConsentService      写入：链锁 → 事件 → 投影 → 失效
│   ├── SubjectRegistry     主体/链头的并发安全创建
│   ├── DecisionService     判定：事实 + 政策目录 → 结论
│   ├── DecisionCache       事实缓存 + 修订号失效
│   ├── PolicyCatalog       生效政策的内存快照
│   ├── PolicyService       政策版本生命周期
│   ├── ReceiptService      凭证生成、规范化签名、链校验
│   └── Hashing             规范化与 HMAC 的唯一实现
├── web/        REST API、管理后台、统一错误处理
├── config/     安全链、配置属性
└── seed/       可复现的演示数据
```
