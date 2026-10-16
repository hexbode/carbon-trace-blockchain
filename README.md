# 碳链通 · 基于区块链的企业碳排放数据认证与追溯系统

面向企业碳排放数据的**填报 → 审核 → 认证 → 上链存证 → 公众核验**全流程平台。

认证存证由 **Solidity 智能合约**承载，合约部署在 **FISCO BCOS 联盟链的 WeBASE** 上，
由 SpringBoot 通过 WeBASE-Front 的 REST 接口调用合约；链数据由联盟链自身持久化
（**数据库不保存任何哈希值**）。公众可通过 **电子防伪码** 在公共端查询与核验证书真伪，
核验以链上存证为唯一依据。

两条核心链上能力：

1. **链上存证与核验**：认证 / 撤销 / 作废都写入存证合约，证书状态（有效 / 已撤销 / 已作废）以链上为准；
2. **合规判定上链**：配额、环比波动、活动数据缺失、排放因子不符、迟报五类规则由合规合约判定，
   合约返回结果位掩码，告警级别与文案仍由后端生成。

平台**不含任何自实现的区块链**（原 Java 自实现链已整体移除）。

---

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite 5 + Element Plus + ECharts + Pinia + Vue Router |
| 后端 | Spring Boot 3.4.3 + MyBatis-Plus 3.5.9 + JDK 17 |
| 数据库 | MySQL 8.x（库名 `carbon_emission`，utf8mb4） |
| 区块链 | FISCO BCOS 2.9.x 联盟链 + WeBASE 1.5.x（WeBASE-Front / WeBASE-Sign）+ Solidity 0.6.10 智能合约 |
| 链上调用 | SpringBoot → WeBASE-Front REST（`/trans/handle` 等），免 SDK 依赖 |
| 鉴权 | JWT（有效期 24 小时）+ 拦截器 + 角色校验 |

---

## 目录结构

```
新建文件夹/
├── houduan/                  后端（Spring Boot）
│   ├── contracts/            Solidity 智能合约源码与部署脚本
│   │   ├── CarbonCertRegistry.sol   认证存证合约（认证 / 撤销 / 作废 / 核验）
│   │   ├── CarbonCompliance.sol     合规判定合约（配额 / 环比 / 漏填 / 因子 / 迟报）
│   │   └── deploy-contracts.ps1     编译 → 部署 → 注册 ABI
│   ├── src/main/java/com/carbon/trace/
│   │   ├── chain/            链网关层（ChainGateway 抽象 + webase 实现 + stub 离线桩）
│   │   ├── common/           工具与全局处理（JWT、异常、电子防伪码等）
│   │   ├── config/           配置（WebMvc、MyBatis-Plus、Jackson）
│   │   ├── controller/       接口层（16 个 Controller）
│   │   ├── entity/ mapper/   实体与 Mapper
│   │   └── service/impl/     业务层
│   ├── src/main/resources/
│   │   ├── application.yml   配置（含 carbon.chain.* 链配置）
│   │   └── chain/            合约 ABI 文件（供 WeBASE 编解码用）
│   ├── uploads/              上传文件（头像，运行时自动创建，不入库）
│   ├── api-tests.ps1         接口回归测试脚本（62 项）
│   └── mvnw / mvnw.cmd       Maven 包装器
├── qianduan/                 前端（Vue 3 + Vite）
│   └── src/
│       ├── api/              接口封装（16 个模块）
│       ├── views/
│       │   ├── enterprise/   企业端（6 页）
│       │   ├── auditor/      审核端（4 页）
│       │   ├── regulator/    监管端（8 页）
│       │   └── public/       公共端（4 页）
│       ├── router/ store/ composables/ utils/
├── database/                 数据库脚本
├── docs/                     项目文档（设计方案、接口清单）
└── README.md
```

---

## 环境要求

- **JDK 17**（后端构建请务必使用 17，详见"常见问题"）
- Maven（直接用项目自带的 `mvnw` / `mvnw.cmd` 即可）
- Node.js 18+
- MySQL 8.x
- **联盟链环境（生产/演示部署需要）**：FISCO BCOS 2.9.x 节点 + WeBASE 1.5.x（WeBASE-Front 5002 / WeBASE-Sign 5004）
  - 本机开发**可不装**：后端默认 `carbon.chain.mode=stub`（离线桩），用于保证应用可启动、接口回归可跑

---

## 快速开始

### 1. 初始化数据库

```sql
CREATE DATABASE carbon_emission DEFAULT CHARACTER SET utf8mb4;
```

脚本说明（`database/`）：

| 脚本 | 内容 |
| --- | --- |
| `carbon_emission_db.sql` | 全量建表 + 初始数据（enterprises / emission_sources / emission_factors / audit_organizations / users / emission_records / report_records / certification_records / alerts） |
| `03_profile_settings.sql` | 个人设置表 `user_settings` |
| `04_regulator_organizations.sql` | 监管机构表 `regulator_organizations` |
| `05_sync_triggers.sql` | emission → report / certification 的数据联动触发器 |
| `06_align_schema.sql`、`07_align_all_schema.sql` | 表结构对齐调整 |
| `08_user_avatar.sql` | 用户头像字段 |

建议执行顺序：`carbon_emission_db.sql` → `03` → `04` → `05` → `06` → `07` → `08`。

> ⚠️ 注意：`carbon_emission_db.sql` 与 `05_sync_triggers.sql` 仍包含**已下线的链表** `blocks`、`block_transactions`。当前实际库结构为 **11 张表、无任何哈希列**，链上存证由联盟链上的智能合约承载（不再有本地链数据文件）。如需用脚本重建库，请先按现状更新这两处。

> 数据库连接配置在 `houduan/src/main/resources/application.yml`，默认 `root / 123456`，请按本机实际修改。

### 2. 启动后端

```powershell
cd houduan
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # 指向本机 JDK 17
.\mvnw.cmd spring-boot:run
```

启动后监听 **8080**。链运行模式由 `carbon.chain.mode` 决定：

- `stub`（默认）：控制台会打印醒目的 WARN 横幅，说明当前是**离线开发桩**（无区块、无挖矿、不落盘、重启即清空）；
- `webase`：控制台打印链网关装配情况、链连通性检查，并在启动完成后输出「链上存证与业务库一致性对账」结果。

### 3. 启动前端

```powershell
cd qianduan
npm install
npm run dev
```

访问 **http://localhost:5173**（Vite 已配置代理：`/api`、`/uploads` → `http://localhost:8080`）。

### 4. 接入联盟链（生产 / 演示部署）

```powershell
# 1) 前置：FISCO BCOS 2.9.x 节点 + WeBASE-Front（5002）已启动；生产模式需 WeBASE-Sign（5004）
Invoke-RestMethod "http://127.0.0.1:5002/WeBASE-Front/1/web3/blockNumber"    # 应返回区块高度

# 2) 创建链上应用账户（Sign 托管，私钥不出 Sign 服务）
Invoke-RestMethod -Method Post "http://127.0.0.1:5002/WeBASE-Front/privateKey?type=2&userName=carbon-backend"   # 返回 signUserId
Invoke-RestMethod "http://127.0.0.1:5002/WeBASE-Front/privateKey/userInfoWithSign?signUserId=<signUserId>"       # 返回账户 address

# 3) 编译 → 部署两个合约 → 注册 ABI，并打印应写回的配置
cd houduan
powershell -ExecutionPolicy Bypass -File .\contracts\deploy-contracts.ps1 `
    -Front http://127.0.0.1:5002 -SignUserId <signUserId> -AppAddress <address> -OutputFile .\src\main\resources\application-local.yml

# 4) 以真实链模式启动（application-local.yml 不入库，包含合约地址与账户）
$env:CARBON_CHAIN_MODE='webase'
$env:CARBON_CHAIN_SIGN_USER_ID='<signUserId>'
.\mvnw.cmd spring-boot:run
```

> ⚠️ 配置里**不要写私钥**：使用 WeBASE-Sign 时后端只持有 `signUserId`；本机真实配置请放在
> `application-local.yml`（已加入 `.gitignore`）或系统环境变量中。

> 📌 迁移旧数据：历史认证记录可通过 `carbon.chain.migration.enabled=true` 一次性写入链上
> （以业务库为准）；执行完请将开关改回 `false`。

---

## 测试账号

| 角色 | 用户名 | 密码 | 说明 |
| --- | --- | --- | --- |
| 企业端 | `enterprise01` | `12345678abc` | 数据填报、认证申请、删除记录 |
| 监管端 | `regulator01` | `123456` | 企业/机构/因子管理、撤销认证、处理告警 |
| 审核端 | `auditor01` | `123456` | 报告审核（通过 / 驳回） |
| 系统管理员 | `admin` | `123456` | 超级角色，拥有上述全部权限 |

---

## 接口测试

后端启动后执行：

```powershell
cd houduan
powershell -ExecutionPolicy Bypass -File api-tests.ps1
```

覆盖 8 个模块共 **62 项**断言：认证与鉴权、公共端、认证记录、角色越权、监管机构 CRUD、企业管理、统计接口、其他业务模块，以及新增的**链模式与合规判定**。全部通过时退出码为 0。

> 说明：在 `stub` 模式下，存量认证记录会由启动播种器灌入离线桩，因此防伪码核验、示例证书等断言与使用真实链时行为一致。

完整的接口清单见 [`docs/接口清单.md`](docs/接口清单.md)。

---

## 核心业务规则

1. **三表同号**：排放记录、报告记录、认证记录共用同一编号（`CR + 6 位序号`），链上以该编号为存证的唯一键。
2. **认证链路**：企业填报 → 自动生成报告（pending）→ 审核通过（certified）→ 生成认证记录并**上链存证**（写入 `CarbonCertRegistry`）；驳回（rejected）后企业可重新申请。
3. **电子防伪码**：填报时随机生成 12 位字符串，是链上存证的映射索引。
   - 查询/核验 **大小写敏感、精确匹配**（仅忽略首尾空格）——合约以 `keccak256(原始字节)` 为索引，天然区分大小写；
   - 交易哈希匹配则忽略大小写与 `0x` 前缀。
4. **核验以链为准**：核验先在链上定位存证，再由排放编号关联认证记录；证书状态（`VALID / REVOKED / INVALID`）以**链上状态**为准，业务表中的防伪码仅作展示回填，被改动不影响核验结果。
5. **上链时机**：链上写入不受数据库事务保护，因此统一延迟到事务提交后（`afterCommit`）执行，避免回滚产生孤儿存证；写入失败会自动重试，最终失败会生成 `chain_failed` 高危告警。
6. **撤销**：仅监管端/管理员可撤销认证，撤销原因必填；撤销同样要**写链**，链上状态变为 `REVOKED` 且为终局状态（不可再次认证复活）；撤销后核验结果失效但证书信息仍可见。
7. **链上只追加、不删除**：删除排放记录只是把链上存证标记为**作废**（`INVALID`），历史留痕不可抹除。
8. **合规判定由合约执行**：年度配额、环比波动（阈值可治理）、活动数据为 0、排放因子不符、迟报五类规则在 `CarbonCompliance` 内判定并返回结果位掩码；告警级别（高/中/低）与中文文案仍由后端生成。链不可用时按 `carbon.chain.compliance.fallback-to-java` 回退到本地规则，保证填报流程不中断。
9. **一致性对账**：启动时与每隔 `carbon.chain.integrity-interval-ms` 对账链上计数与业务库记录（分别比对有效 / 已撤销）；已作废的存证只存在于链上，差额属预期。

---

## 常见问题

**Q：IDE 里 `service/impl` 等目录大面积报"找不到符号"？**

A：多为 Lombok 未生效或语言服务器状态问题，按以下顺序排查：

1. 后端构建必须用 **JDK 17**。JDK 23+ 的 javac 默认不再执行 classpath 上的注解处理器，会导致 Lombok 不生成 `getter/setter`。
2. 若 IDE 仍报错，执行命令面板的 `Java: Clean Java Language Server Workspace` 后重载窗口。

**Q：改了业务表里的电子防伪码，前端还能查到吗？**

A：查询/核验走链上存证，业务表改动不影响核验结果（这是设计预期）。

**Q：WeBASE 没启动会怎样？**

A：`carbon.chain.mode=webase` 时链相关调用会返回 **503**（"联盟链服务不可用"），认证/撤销/核验会失败；
但填报流程中的**告警判定**会按 `fallback-to-java` 回退到本地规则，不会中断填报。
`carbon.chain.mode=stub` 时完全不依赖 WeBASE，但链上没有真实存证（仅供本机开发与回归）。

**Q：区块列表与交易哈希从哪来？**

A：区块来自 `GET /api/blockchain`（后端调 WeBASE-Front 的 `web3/blockByNumber`）；
交易哈希无法由合约自述，写交易时取交易回执，历史查询时按区块反查 `CertCreated` 事件，并做缓存。

**Q：历史防伪码在链上查不到怎么办？**

A：把 `carbon.chain.migration.enabled` 置为 `true` 启动一次，历史认证记录会自动写入合约
（以业务库为准）；迁移完成后请把开关改回 `false`。

**Q：`houduan/data/blockchain.json` 呢？**

A：该文件（旧自实现链的历史数据）已随项目清理删除，不再参与运行。迁移工具**以业务库为准**，
文件不存在时仅跳过「防伪码兜底读取」；若确有需要，可从备份取回并放到
`carbon.chain.migration.legacy-file` 指定的路径。

---

## 已知待办

- `database/` 下的 SQL 脚本仍保留已下线的两张链表的建表语句（`blocks`、`block_transactions`）；当前实际库结构为 **11 张表、无任何哈希列**。如需以脚本重建库，建议先按现状更新这些脚本。
- 合约治理：`owner` / `writer` 的授权变更（`addWriter` / `removeWriter`）目前通过 WeBASE-Front 控制台或脚本执行，尚未提供平台内管理入口。
- 合约升级：当前采用「部署新合约 + 迁移历史数据」的方式，未引入代理合约（可升级模式）。
