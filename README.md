# 碳链通 · 基于区块链的企业碳排放数据认证与追溯系统

面向企业碳排放数据的**填报 → 审核 → 认证 → 上链存证 → 公众核验**全流程平台。

认证存证保存在自实现的区块链中，链数据以文件形式持久化（**数据库不保存任何哈希值**）。
公众可通过 **电子防伪码** 在公共端查询与核验证书真伪，核验以链上存证为唯一依据。

---

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite 5 + Element Plus + ECharts + Pinia + Vue Router |
| 后端 | Spring Boot 3.4.3 + MyBatis-Plus 3.5.9 + JDK 17 |
| 数据库 | MySQL 8.x（库名 `carbon_emission`，utf8mb4） |
| 区块链 | 自实现链：SHA-256 + 默克尔根 + PoW（难度 2），每 4 笔交易打包一个区块 |
| 鉴权 | JWT（有效期 24 小时）+ 拦截器 + 角色校验 |

---

## 目录结构

```
新建文件夹/
├── houduan/                  后端（Spring Boot）
│   ├── src/main/java/com/carbon/trace/
│   │   ├── chain/            区块链核心（ChainStore / Block / BlockTransaction 等）
│   │   ├── common/           工具与全局处理（JWT、异常、哈希工具等）
│   │   ├── config/           配置（WebMvc、MyBatis-Plus、Jackson）
│   │   ├── controller/       接口层（15 个 Controller）
│   │   ├── entity/ mapper/   实体与 Mapper
│   │   └── service/impl/     业务层
│   ├── src/main/resources/application.yml
│   ├── data/blockchain.json  链上数据文件（区块 + 交易）
│   ├── uploads/              上传文件（头像）
│   ├── api-tests.ps1         接口回归测试脚本（49 项）
│   └── mvnw / mvnw.cmd       Maven 包装器
├── qianduan/                 前端（Vue 3 + Vite）
│   └── src/
│       ├── api/              接口封装（15 个模块）
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

> ⚠️ 注意：`carbon_emission_db.sql` 与 `05_sync_triggers.sql` 仍包含**已下线的链表** `blocks`、`block_transactions`。当前实际库结构为 **11 张表、无任何哈希列**，链数据保存在 `houduan/data/blockchain.json`。如需用脚本重建库，请先按现状更新这两处。

> 数据库连接配置在 `houduan/src/main/resources/application.yml`，默认 `root / 123456`，请按本机实际修改。

### 2. 启动后端

```powershell
cd houduan
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # 指向本机 JDK 17
.\mvnw.cmd spring-boot:run
```

启动后监听 **8080**，控制台会打印链数据加载情况与「链上数据与业务库一致性校验」结果。

### 3. 启动前端

```powershell
cd qianduan
npm install
npm run dev
```

访问 **http://localhost:5173**（Vite 已配置代理：`/api`、`/uploads` → `http://localhost:8080`）。

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

覆盖 8 个模块共 **49 项**断言：认证与鉴权、公共端、认证记录、角色越权、监管机构 CRUD、企业管理、统计接口、其他业务模块。全部通过时退出码为 0。

完整的接口清单见 [`docs/接口清单.md`](docs/接口清单.md)。

---

## 核心业务规则

1. **三表同号**：排放记录、报告记录、认证记录共用同一编号（`CR + 6 位序号`）。
2. **认证链路**：企业填报 → 自动生成报告（pending）→ 审核通过（certified）→ 生成认证记录并**上链存证**；驳回（rejected）后企业可重新申请。
3. **电子防伪码**：填报时随机生成 12 位字符串，是链上哈希的映射索引。
   - 查询/核验 **大小写敏感、精确匹配**（仅忽略首尾空格）；
   - 交易哈希匹配则忽略大小写与 `0x` 前缀。
4. **核验以链为准**：核验先定位链上交易，再由排放编号关联认证记录判断是否已撤销；业务表中的防伪码与哈希仅作展示回填，被改动不影响核验结果。
5. **链一致性**：链数据保存在文件中、不受数据库事务保护，因此链写入延迟到事务提交后（`afterCommit`）执行，避免回滚产生孤儿交易。
6. **撤销**：仅监管端/管理员可撤销认证，撤销原因必填；撤销后核验结果失效但证书信息仍可见。

---

## 常见问题

**Q：IDE 里 `service/impl` 等目录大面积报"找不到符号"？**

A：多为 Lombok 未生效或语言服务器状态问题，按以下顺序排查：

1. 后端构建必须用 **JDK 17**。JDK 23+ 的 javac 默认不再执行 classpath 上的注解处理器，会导致 Lombok 不生成 `getter/setter`。
2. 若 IDE 仍报错，执行命令面板的 `Java: Clean Java Language Server Workspace` 后重载窗口。

**Q：改了业务表里的电子防伪码，前端还能查到吗？**

A：查询/核验走链上存证，业务表改动不影响核验结果（这是设计预期）。

**Q：链数据文件被删了怎么办？**

A：启动时若文件缺失会重建创世块，并在日志输出 **ERROR 级告警**（历史存证可能已丢失）。请勿将 `houduan/data/blockchain.json` 纳入忽略或清理范围。

---

## 已知待办

- `database/` 下的 SQL 脚本仍保留已下线的两张链表的建表语句（`blocks`、`block_transactions`）；当前实际库结构为 **11 张表、无任何哈希列**。如需以脚本重建库，建议先按现状更新这些脚本。
