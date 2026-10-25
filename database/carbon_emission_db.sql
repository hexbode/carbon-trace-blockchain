-- ============================================================
-- 碳链通 · 基于区块链的企业碳排放数据认证与追溯系统
-- MySQL 8.x 数据库脚本（表结构 + 初始数据）
-- 由前端 src/mock/data.js 与 src/utils/blockchain.js 的真实数据结构导出
-- 字符集：utf8mb4（支持 tCO₂ 中的下标 ₂）
--
-- 【用途】
--   基准建表脚本：创建碳链通业务库的核心表，并写入一套可直接演示的初始数据
--   （4 家企业、48 条 2024 年度排放记录，以及报告/认证/告警等派生数据）。
--
-- 【执行顺序】（脚本编号即推荐顺序）
--   0) 先建库：CREATE DATABASE `carbon_emission` DEFAULT CHARSET utf8mb4;
--   1) carbon_emission_db.sql          基准表结构 + 演示数据（本文件）
--   2) 03_profile_settings.sql         users 增加 email/signature，新建 user_settings
--   3) 04_regulator_organizations.sql  新建监管机构表
--   4) 05_sync_triggers.sql            排放/报告/认证/链上交易四表联动触发器
--   5) 06_align_schema.sql             放宽 report_records / certification_records 的空值约束
--   6) 07_align_all_schema.sql         放宽其余各表的空值约束与字段长度
--   7) 08_user_avatar.sql              users 增加 avatar
--
-- 【依赖】
--   MySQL 8.0+；库 carbon_emission 需已存在（本文件不含 CREATE DATABASE / USE，
--   执行时须显式指定库，例如：mysql -uroot -p -D carbon_emission < carbon_emission_db.sql）。
--
-- 【幂等性】
--   结构层面可重复执行：每张表都是 DROP TABLE IF EXISTS 后重建；
--   但数据层面是破坏性的——重跑会清空这些表的现有数据并重置为脚本内置演示数据。
--   请勿在有真实业务数据的库上直接重跑。
--
-- 【重要：链上链表已下线】
--   本文件中的 `blocks`（区块表）与 `block_transactions`（区块交易表）属于早期
--   本地模拟链表，链模式改造后（stub 离线桩 / WeBASE 真实联盟链）后端已不再读写。
--   当前实际数据库为 11 张表：
--     enterprises、emission_sources、emission_factors、audit_organizations、
--     users、user_settings、regulator_organizations、emission_records、
--     report_records、certification_records、alerts
--   与实库的差异：
--     · 本文件的 blocks / block_transactions 在实库中不存在，应删除；
--     · 实库的 user_settings、regulator_organizations 由 03、04 脚本创建，本文件未包含。
--   重建库前必须先更新本脚本，否则重建结果与实际库结构不一致。
-- ============================================================

-- 会话字符集设为 utf8mb4，确保脚本内 tCO₂ 等特殊字符正确入库
SET NAMES utf8mb4;
-- 关闭外键检查：DROP 阶段各表互有外键，关闭后才能安全地按倒序删除；脚本末尾恢复
SET FOREIGN_KEY_CHECKS = 0;

-- 按依赖关系倒序删除，便于重复执行
-- 【已下线】block_transactions / blocks 为本地模拟链表，实际库（11 张表）中不存在，
--           重建库前应连同文件后半部分的对应 INSERT 一起删除。
DROP TABLE IF EXISTS `block_transactions`;
DROP TABLE IF EXISTS `blocks`;
DROP TABLE IF EXISTS `certification_records`;
DROP TABLE IF EXISTS `report_records`;
DROP TABLE IF EXISTS `emission_records`;
DROP TABLE IF EXISTS `alerts`;
DROP TABLE IF EXISTS `users`;
DROP TABLE IF EXISTS `emission_factors`;
DROP TABLE IF EXISTS `emission_sources`;
DROP TABLE IF EXISTS `audit_organizations`;
DROP TABLE IF EXISTS `enterprises`;

-- ============================================================
-- 一、表结构
-- ============================================================

-- ------------------------------------------------------------
-- 1. 企业信息表 enterprises —— 企业端主体档案
--    约束：id 为企业编号（业务主键）；credit_code（统一社会信用代码）全库唯一；
--          status 仅 active / inactive 两态，停用企业仍保留历史排放数据。
--    索引：uk_enterprise_credit 防止重复登记；idx_enterprise_industry 支撑按行业筛选与统计。
--    外键：被 users、emission_records、report_records、certification_records、alerts 引用，
--          删除企业前须先清理关联业务数据，否则报外键错误 1451。
--    字段分组：① 主体标识 ② 联系与注册信息 ③ 状态
-- ------------------------------------------------------------
CREATE TABLE `enterprises` (
  -- ① 主体标识
  `id`            VARCHAR(20)  NOT NULL                COMMENT '企业编号',
  `name`          VARCHAR(100) NOT NULL                COMMENT '企业名称',
  `industry`      VARCHAR(50)  NOT NULL                COMMENT '所属行业',
  `credit_code`   VARCHAR(30)  NOT NULL                COMMENT '统一社会信用代码',
  -- ② 联系与注册信息（均可空，新增企业时可暂不填写）
  `contact`       VARCHAR(50)           DEFAULT NULL   COMMENT '联系人',
  `phone`         VARCHAR(30)           DEFAULT NULL   COMMENT '联系电话',
  `address`       VARCHAR(200)          DEFAULT NULL   COMMENT '注册地址',
  `scope`         VARCHAR(200)          DEFAULT NULL   COMMENT '核算范围',
  `registered_at` DATE                  DEFAULT NULL   COMMENT '注册时间',
  -- ③ 状态
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active 正常 / inactive 停用',
  -- 主键：企业编号（业务编号，非自增）
  PRIMARY KEY (`id`),
  -- 统一社会信用代码唯一，作为企业实名登记的去重依据
  UNIQUE KEY `uk_enterprise_credit` (`credit_code`),
  -- 行业索引：列表筛选、行业分布统计
  KEY `idx_enterprise_industry` (`industry`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业信息表';

-- ------------------------------------------------------------
-- 2. 排放源字典表 emission_sources —— 排放源与默认排放因子对照
--    约束：所有字段均 NOT NULL；code 为业务主键（S01~S05）。
--    外键：emission_records.source_code 引用本表，新增排放源须先在此登记。
--    字段分组：① 标识与名称 ② 默认因子与单位
-- ------------------------------------------------------------
CREATE TABLE `emission_sources` (
  -- ① 标识与名称
  `code`   VARCHAR(10)  NOT NULL COMMENT '排放源编码',
  `name`   VARCHAR(50)  NOT NULL COMMENT '排放源名称',
  -- ② 默认因子与单位：仅作推荐值，实际核算以 emission_records.emission_factor 为准
  `factor` DECIMAL(10,4) NOT NULL COMMENT '默认排放因子',
  `unit`   VARCHAR(30)  NOT NULL COMMENT '排放因子单位',
  -- 主键：排放源编码
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排放源字典表';

-- ------------------------------------------------------------
-- 3. 碳排放因子库 emission_factors —— 监管端维护的标准因子
--    约束：category 限定化石燃料/购入能源/工业过程；status=inactive 表示停用，
--          停用后仍保留历史引用，不物理删除。
--    索引：idx_factor_category 支撑按类别筛选与因子下拉框过滤。
--    字段分组：① 标识 ② 因子定义 ③ 维护信息
-- ------------------------------------------------------------
CREATE TABLE `emission_factors` (
  -- ① 标识
  `id`         INT           NOT NULL                COMMENT '主键',
  -- ② 因子定义
  `source`     VARCHAR(100)  NOT NULL                COMMENT '排放源名称',
  `category`   VARCHAR(30)   NOT NULL                COMMENT '类别：化石燃料/购入能源/工业过程',
  `factor`     DECIMAL(10,4) NOT NULL                COMMENT '排放因子取值',
  `unit`       VARCHAR(30)   NOT NULL                COMMENT '单位',
  `standard`   VARCHAR(50)            DEFAULT NULL   COMMENT '依据标准',
  -- ③ 维护信息
  `updated_at` DATE                   DEFAULT NULL   COMMENT '更新时间',
  `status`     VARCHAR(20)   NOT NULL DEFAULT 'active' COMMENT '状态：active 启用 / inactive 停用',
  -- 主键：自增代理键
  PRIMARY KEY (`id`),
  -- 类别索引：按化石燃料/购入能源/工业过程分组展示与筛选
  KEY `idx_factor_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳排放因子库表';

-- ------------------------------------------------------------
-- 4. 审核机构表 audit_organizations —— 第三方核查/认证机构档案
--    约束：id 为机构编号（业务主键）；short_name/code 可空，便于兼容历史数据。
--    外键：users.org_id 引用本表 id，删除机构前须先处理其审核人员账号。
--    字段分组：① 机构标识 ② 联系人与资质 ③ 状态与入驻时间
-- ------------------------------------------------------------
CREATE TABLE `audit_organizations` (
  -- ① 机构标识
  `id`            VARCHAR(20)  NOT NULL              COMMENT '机构编号',
  `name`          VARCHAR(100) NOT NULL              COMMENT '机构名称',
  `short_name`    VARCHAR(50)           DEFAULT NULL COMMENT '机构简称',
  `code`          VARCHAR(20)           DEFAULT NULL COMMENT '机构代码',
  -- ② 联系人与资质
  `contact`       VARCHAR(50)           DEFAULT NULL COMMENT '联系人',
  `phone`         VARCHAR(30)           DEFAULT NULL COMMENT '联系电话',
  `qualification` VARCHAR(50)           DEFAULT NULL COMMENT '资质证书号',
  `scope`         VARCHAR(200)          DEFAULT NULL COMMENT '业务范围',
  -- ③ 状态与入驻时间
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态',
  `created_at`    DATE                  DEFAULT NULL COMMENT '入驻时间',
  -- 主键：机构编号（业务编号）
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核机构表';

-- ------------------------------------------------------------
-- 5. 系统用户表 users —— 四类角色共表
--    角色归属：enterprise 绑定 enterprise_id；auditor 绑定 org_id；
--              regulator / admin 两者均为空。
--    索引：uk_user_username 防重复账号；idx_user_role 支撑按角色查询与登录校验。
--    外键：enterprise_id → enterprises.id、org_id → audit_organizations.id。
--    安全提示：password 为演示用明文（默认 123456），生产环境必须改为哈希存储。
--    字段分组：① 登录凭证 ② 身份与归属 ③ 联系方式与状态
--    说明：03 脚本追加 email/signature，08 脚本追加 avatar。
-- ------------------------------------------------------------
CREATE TABLE `users` (
  -- ① 登录凭证
  `id`            INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '登录账号',
  `password`      VARCHAR(100) NOT NULL DEFAULT '123456' COMMENT '登录密码（演示用明文）',
  -- ② 身份与归属
  `name`          VARCHAR(50)           DEFAULT NULL   COMMENT '姓名',
  `role`          VARCHAR(20)  NOT NULL                COMMENT '角色：enterprise/auditor/regulator/admin',
  `enterprise_id` VARCHAR(20)           DEFAULT NULL   COMMENT '所属企业（企业用户）',
  `org_id`        VARCHAR(20)           DEFAULT NULL   COMMENT '所属机构（审核人员）',
  -- ③ 联系方式与状态
  `phone`         VARCHAR(30)           DEFAULT NULL   COMMENT '联系电话',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态',
  `created_at`    DATE                  DEFAULT NULL   COMMENT '创建时间',
  -- 主键：自增代理键，同时是 user_settings.user_id 的外键目标
  PRIMARY KEY (`id`),
  -- 登录账号唯一，避免同名账号并存
  UNIQUE KEY `uk_user_username` (`username`),
  -- 角色索引：权限校验与按角色统计
  KEY `idx_user_role` (`role`),
  -- 企业外键：企业用户必须归属一家已登记企业
  CONSTRAINT `fk_user_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprises` (`id`),
  -- 机构外键：审核人员必须归属一家已登记审核机构
  CONSTRAINT `fk_user_org` FOREIGN KEY (`org_id`) REFERENCES `audit_organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ------------------------------------------------------------
-- 6. 碳排放数据记录表 emission_records —— 全链路主表
--    业务：企业按「报告期（YYYY-MM）× 排放源」提交的核算明细。
--          report_records、certification_records 均由本表派生，05 脚本用触发器维护联动。
--    约束：amount 由后端按 activity_data × emission_factor 计算后落库（单位 tCO₂），
--          非数据库生成列；fingerprint 为 SHA-256 电子防伪码，用于公开核验。
--    索引：idx_em_enterprise 企业维度列表/统计；idx_em_period 按报告期筛选；
--          idx_em_status 待审核/已认证队列；idx_em_source 按排放源汇总。
--    外键：enterprise_id → enterprises.id；source_code → emission_sources.code。
--    字段分组：① 标识与归属 ② 报告期与排放源 ③ 核算数据 ④ 认证状态与防伪
--              ⑤ 提交信息
-- ------------------------------------------------------------
CREATE TABLE `emission_records` (
  -- ① 标识与归属
  `id`              VARCHAR(20)   NOT NULL              COMMENT '排放记录编号',
  `enterprise_id`   VARCHAR(20)   NOT NULL              COMMENT '企业编号',
  `enterprise_name` VARCHAR(100)  NOT NULL              COMMENT '企业名称（冗余，便于列表展示）',
  -- ② 报告期与排放源
  `period`          VARCHAR(10)   NOT NULL              COMMENT '报告期，格式 YYYY-MM',
  `source_code`     VARCHAR(10)   NOT NULL              COMMENT '排放源编码',
  `source_name`     VARCHAR(50)   NOT NULL              COMMENT '排放源名称',
  -- ③ 核算数据：活动数据 × 排放因子 = 排放量，单位固定 tCO₂
  `activity_data`   DECIMAL(12,2) NOT NULL              COMMENT '活动数据',
  `activity_unit`   VARCHAR(20)            DEFAULT NULL COMMENT '活动数据单位',
  `emission_factor` DECIMAL(10,4) NOT NULL              COMMENT '本次核算使用的排放因子',
  `amount`          DECIMAL(14,2) NOT NULL              COMMENT '碳排放量 = 活动数据 × 排放因子',
  `unit`            VARCHAR(10)   NOT NULL DEFAULT 'tCO₂' COMMENT '排放量单位',
  -- ④ 认证状态与防伪
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '认证状态：certified 已认证 / pending 待审核 / rejected 已驳回',
  `fingerprint`     VARCHAR(70)            DEFAULT NULL COMMENT '电子防伪码（SHA-256 哈希）',
  -- ⑤ 提交信息
  `created_at`      DATETIME               DEFAULT NULL COMMENT '提交时间',
  `reporter`        VARCHAR(50)            DEFAULT NULL COMMENT '填报人',
  -- 主键：排放记录编号（业务编号）
  PRIMARY KEY (`id`),
  -- 企业维度索引：企业端列表、按企业汇总统计
  KEY `idx_em_enterprise` (`enterprise_id`),
  -- 报告期索引：按月筛选与趋势统计
  KEY `idx_em_period` (`period`),
  -- 状态索引：待审核/已驳回工作队列
  KEY `idx_em_status` (`status`),
  -- 排放源索引：按排放源汇总排放量
  KEY `idx_em_source` (`source_code`),
  -- 企业外键
  CONSTRAINT `fk_em_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprises` (`id`),
  -- 排放源外键：必须使用已登记排放源编码
  CONSTRAINT `fk_em_source` FOREIGN KEY (`source_code`) REFERENCES `emission_sources` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳排放数据记录表';

-- ------------------------------------------------------------
-- 7. 报告提交记录表 report_records —— 排放记录的审核流转载体
--    业务：新增排放记录时由 05 脚本的 trg_emission_ai 自动生成同号报告（id = 排放记录 id），
--          status 从 pending 流转到 certified/rejected，审核人/意见/时间在审核时回填。
--    约束：id 与 emission_records.id 一一对应；emission_id 可空以兼容历史报告；
--          status 无数据库默认值，由业务或触发器显式给出。
--    索引：idx_rpt_enterprise 企业维度；idx_rpt_status 审核队列；idx_rpt_emission 反查关联排放。
--    外键：emission_id → emission_records.id；enterprise_id → enterprises.id。
--    字段分组：① 标识与关联 ② 报告内容 ③ 审核流转
-- ------------------------------------------------------------
CREATE TABLE `report_records` (
  -- ① 标识与关联
  `id`              VARCHAR(20)   NOT NULL              COMMENT '报告编号',
  `emission_id`     VARCHAR(20)            DEFAULT NULL COMMENT '关联排放记录编号',
  `enterprise_id`   VARCHAR(20)   NOT NULL              COMMENT '企业编号',
  `enterprise_name` VARCHAR(100)  NOT NULL              COMMENT '企业名称',
  `period`          VARCHAR(10)   NOT NULL              COMMENT '报告期 YYYY-MM',
  -- ② 报告内容
  `source_name`     VARCHAR(50)            DEFAULT NULL COMMENT '排放源名称',
  `amount`          DECIMAL(14,2) NOT NULL              COMMENT '排放量',
  `status`          VARCHAR(20)   NOT NULL              COMMENT '审核状态：certified/pending/rejected',
  `fingerprint`     VARCHAR(70)            DEFAULT NULL COMMENT '电子防伪码',
  `submitter`       VARCHAR(50)            DEFAULT NULL COMMENT '提交人',
  `submitted_at`    DATETIME               DEFAULT NULL COMMENT '提交时间',
  -- ③ 审核流转（待审核时 auditor/audited_at/audit_opinion 为空）
  `auditor`         VARCHAR(50)            DEFAULT NULL COMMENT '审核人',
  `audited_at`      DATETIME               DEFAULT NULL COMMENT '审核时间',
  `audit_opinion`   TEXT                            COMMENT '审核意见',
  -- 主键：报告编号（与排放记录同号）
  PRIMARY KEY (`id`),
  -- 企业维度索引
  KEY `idx_rpt_enterprise` (`enterprise_id`),
  -- 审核状态索引：待审核列表
  KEY `idx_rpt_status` (`status`),
  -- 排放记录反查索引：按 emission_id 定位派生报告
  KEY `idx_rpt_emission` (`emission_id`),
  -- 排放记录外键
  CONSTRAINT `fk_rpt_emission` FOREIGN KEY (`emission_id`) REFERENCES `emission_records` (`id`),
  -- 企业外键
  CONSTRAINT `fk_rpt_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprises` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告提交记录表';

-- ------------------------------------------------------------
-- 8. 认证记录表 certification_records —— 上链存证的认证凭证
--    业务：审核通过后生成，记录链上交易哈希与区块高度，是公开查询/二维码核验的依据。
--    约束：uk_cert_emission 保证一条排放记录最多只有一张有效认证凭证；
--          status 支持 valid → revoked 的撤销留痕（保留撤销原因与时间）。
--    索引：uk_cert_emission 防重复发证；idx_cert_enterprise 企业维度；
--          idx_cert_block 按区块高度回溯（链表下线后仅作历史字段保留）。
--    外键：emission_id → emission_records.id；enterprise_id → enterprises.id。
--    字段分组：① 标识与关联 ② 认证内容 ③ 链上存证 ④ 审核与撤销
-- ------------------------------------------------------------
CREATE TABLE `certification_records` (
  -- ① 标识与关联
  `id`              VARCHAR(20)   NOT NULL              COMMENT '认证编号',
  `emission_id`     VARCHAR(20)            DEFAULT NULL COMMENT '关联排放记录编号',
  `enterprise_id`   VARCHAR(20)   NOT NULL              COMMENT '企业编号',
  `enterprise_name` VARCHAR(100)  NOT NULL              COMMENT '企业名称',
  `period`          VARCHAR(10)   NOT NULL              COMMENT '报告期 YYYY-MM',
  -- ② 认证内容
  `amount`          DECIMAL(14,2) NOT NULL              COMMENT '认证排放量',
  `fingerprint`     VARCHAR(70)            DEFAULT NULL COMMENT '电子防伪码',
  -- ③ 链上存证（tx_hash/block_index 由链网关回填；stub 模式下为桩内模拟值）
  `tx_hash`         VARCHAR(70)            DEFAULT NULL COMMENT '区块链交易哈希',
  `block_index`     INT                    DEFAULT NULL COMMENT '所在区块高度',
  -- ④ 审核与撤销
  `auditor`         VARCHAR(50)            DEFAULT NULL COMMENT '审核人',
  `audit_opinion`   TEXT                            COMMENT '审核意见',
  `certified_at`    DATETIME               DEFAULT NULL COMMENT '认证时间',
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'valid' COMMENT '状态：valid 有效 / revoked 已撤销',
  `revoke_reason`   VARCHAR(255)           DEFAULT NULL COMMENT '撤销原因',
  `revoked_at`      DATETIME               DEFAULT NULL COMMENT '撤销时间',
  -- 主键：认证编号
  PRIMARY KEY (`id`),
  -- 一条排放记录仅允许一张认证凭证，防止重复发证
  UNIQUE KEY `uk_cert_emission` (`emission_id`),
  -- 企业维度索引：企业证书列表
  KEY `idx_cert_enterprise` (`enterprise_id`),
  -- 区块高度索引：历史链上回溯（链表下线后仅作兼容保留）
  KEY `idx_cert_block` (`block_index`),
  -- 排放记录外键
  CONSTRAINT `fk_cert_emission` FOREIGN KEY (`emission_id`) REFERENCES `emission_records` (`id`),
  -- 企业外键
  CONSTRAINT `fk_cert_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprises` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认证记录表';

-- ------------------------------------------------------------
-- 9. 区块链-区块表 blocks【已下线的本地模拟链表】
--    ⚠ 该表属于早期「自建 PoW 模拟链」实现，链模式改造（stub / WeBASE）后
--      后端不再读写；当前实际库（11 张表）中不存在本表。
--    保留原因：仅作为历史演示数据与旧版前端结构的存档说明。
--    重建库前应删除本表及文件末尾对应的 INSERT。
--    字段分组：① 链结构（block_index/hash/previous_hash/timestamp）
--              ② PoW 与打包信息（merkle_root/nonce/miner）
--              ③ 统计（tx_count）
-- ------------------------------------------------------------
CREATE TABLE `blocks` (
  -- ① 链结构
  `block_index`   INT          NOT NULL COMMENT '区块高度（0 为创世区块）',
  `hash`          VARCHAR(70)  NOT NULL COMMENT '区块哈希',
  `previous_hash` VARCHAR(70)  NOT NULL COMMENT '前一区块哈希',
  `timestamp`     BIGINT       NOT NULL COMMENT '区块时间戳（毫秒）',
  -- ② PoW 与打包信息
  `merkle_root`   VARCHAR(70)           DEFAULT NULL COMMENT '默克尔根',
  `nonce`         INT          NOT NULL DEFAULT 0 COMMENT 'PoW 随机数',
  `miner`         VARCHAR(50)           DEFAULT NULL COMMENT '打包节点',
  -- ③ 统计
  `tx_count`      INT          NOT NULL DEFAULT 0 COMMENT '区块内交易数',
  -- 主键：区块高度（链式递增，创世区块为 0）
  PRIMARY KEY (`block_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区块链-区块表';

-- ------------------------------------------------------------
-- 10. 区块链-区块交易表 block_transactions【已下线的本地模拟链表】
--    ⚠ 同 blocks：后端已不再读写，当前实际库中不存在本表。
--    原业务：每 4 笔认证交易打包进一个区块，与 certification_records 通过
--            emission_id / tx_hash 对应。
--    索引：uk_tx_hash 防交易重复；idx_bt_block 按区块聚合交易。
--    外键：block_index → blocks.block_index（两表需一起删除，否则外键失配）。
--    字段分组：① 交易标识与所属区块 ② 业务快照（排放记录/企业/报告期/排放量/防伪码）
--              ③ 审核人
-- ------------------------------------------------------------
CREATE TABLE `block_transactions` (
  -- ① 交易标识与所属区块
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `block_index`     INT           NOT NULL              COMMENT '所属区块高度',
  `tx_hash`         VARCHAR(70)   NOT NULL              COMMENT '交易哈希',
  -- ② 业务快照
  `emission_id`     VARCHAR(20)            DEFAULT NULL COMMENT '关联排放记录编号',
  `enterprise_name` VARCHAR(100)           DEFAULT NULL COMMENT '企业名称',
  `period`          VARCHAR(10)            DEFAULT NULL COMMENT '报告期',
  `amount`          DECIMAL(14,2)          DEFAULT NULL COMMENT '排放量',
  `fingerprint`     VARCHAR(70)            DEFAULT NULL COMMENT '电子防伪码',
  -- ③ 审核人
  `auditor`         VARCHAR(50)            DEFAULT NULL COMMENT '审核人',
  -- 主键：自增代理键
  PRIMARY KEY (`id`),
  -- 交易哈希唯一，防止同一笔交易重复入块
  UNIQUE KEY `uk_tx_hash` (`tx_hash`),
  -- 区块维度索引：按区块高度查询交易列表
  KEY `idx_bt_block` (`block_index`),
  -- 区块外键：交易必须归属一个已存在的区块
  CONSTRAINT `fk_bt_block` FOREIGN KEY (`block_index`) REFERENCES `blocks` (`block_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区块链-区块交易表';

-- ------------------------------------------------------------
-- 11. 异常告警表 alerts —— 监管端风险提示
--    业务：按类型（数据异常/因子不符/迟报）与级别（高/中/低）记录待处理事项，
--          status 由 pending 流转到 resolved。
--    索引：idx_alert_enterprise 企业维度；idx_alert_status 待处理队列；idx_alert_level 按级别筛选。
--    外键：enterprise_id → enterprises.id，可空（允许全局告警）。
--    字段分组：① 标识与归属 ② 告警内容 ③ 状态与时间
-- ------------------------------------------------------------
CREATE TABLE `alerts` (
  -- ① 标识与归属
  `id`              VARCHAR(20)  NOT NULL              COMMENT '告警编号',
  `enterprise_id`   VARCHAR(20)           DEFAULT NULL COMMENT '企业编号',
  `enterprise_name` VARCHAR(100)          DEFAULT NULL COMMENT '企业名称',
  -- ② 告警内容
  `type`            VARCHAR(30)  NOT NULL              COMMENT '告警类型：data_anomaly/factor_mismatch/late_report',
  `type_label`      VARCHAR(30)           DEFAULT NULL COMMENT '类型中文名',
  `level`           VARCHAR(10)  NOT NULL              COMMENT '级别：high 高危 / medium 中危 / low 低危',
  `message`         VARCHAR(300)          DEFAULT NULL COMMENT '告警内容',
  `period`          VARCHAR(10)           DEFAULT NULL COMMENT '涉及报告期',
  -- ③ 状态与时间
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending 待处理 / resolved 已处理',
  `created_at`      DATETIME              DEFAULT NULL COMMENT '触发时间',
  -- 主键：告警编号
  PRIMARY KEY (`id`),
  -- 企业维度索引
  KEY `idx_alert_enterprise` (`enterprise_id`),
  -- 状态索引：待处理告警列表
  KEY `idx_alert_status` (`status`),
  -- 级别索引：高危告警优先展示
  KEY `idx_alert_level` (`level`),
  -- 企业外键（可空，允许不绑定企业的全局告警）
  CONSTRAINT `fk_alert_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprises` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常告警表';

-- 恢复外键检查，后续 DML 仍受外键约束保护
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 二、初始数据（演示数据）
--    顺序按外键依赖排列：字典/主体 → 用户 → 排放 → 报告 → 认证 → 链表 → 告警，
--    因此可在外键检查开启状态下直接执行。
--    这些数据用于前端演示与接口冒烟测试，日期/数值均为固定样例值。
--    注意：下方「区块链-区块」「区块链-区块交易」两段 INSERT 对应已下线链表，
--          重建库前应与建表语句一并删除。
-- ============================================================


-- 企业信息（前端静态定义，原样导出）
INSERT INTO `enterprises` (`id`, `name`, `industry`, `credit_code`, `contact`, `phone`, `address`, `scope`, `registered_at`, `status`) VALUES
  ('ENT20240001', '华东钢铁制造有限公司', '钢铁制造', '91310000MA1FLX8J3K', '张建国', '138-0000-1001', '上海市宝山区钢铁大道88号', '钢铁冶炼、轧制', '2023-03-15', 'active'),
  ('ENT20240002', '绿能化工集团', '化工', '91320100MA1GRL2B5N', '李明华', '139-0000-2002', '江苏省南京市化学工业园区66号', '基础化学原料制造', '2023-05-22', 'active'),
  ('ENT20240003', '南方水泥股份有限公司', '建材', '91440300MA5EPC7D9Q', '王秀英', '137-0000-3003', '广东省深圳市南山区科技园12栋', '水泥制造', '2023-07-10', 'active'),
  ('ENT20240004', '东方电力集团', '电力', '91110100MA01A2B3C4', '赵志强', '136-0000-4004', '北京市朝阳区电力大厦', '火力发电', '2023-09-01', 'active');

-- 排放源字典（前端静态定义，原样导出）
INSERT INTO `emission_sources` (`code`, `name`, `factor`, `unit`) VALUES
  ('S01', '燃煤锅炉', 2.66, 'tCO₂/t煤'),
  ('S02', '天然气燃烧', 2.16, 'tCO₂/万m³'),
  ('S03', '柴油消耗', 3.09, 'tCO₂/t柴油'),
  ('S04', '电力消耗', 0.581, 'tCO₂/MWh'),
  ('S05', '工业过程排放', 0.86, 'tCO₂/t产品');

-- 碳排放因子库（前端静态定义，原样导出）
INSERT INTO `emission_factors` (`id`, `source`, `category`, `factor`, `unit`, `standard`, `updated_at`, `status`) VALUES
  (1, '原煤', '化石燃料', 1.9003, 'tCO₂/t', 'IPCC 2006', '2024-01-15', 'active'),
  (2, '柴油', '化石燃料', 3.0959, 'tCO₂/t', 'IPCC 2006', '2024-01-15', 'active'),
  (3, '天然气', '化石燃料', 2.1622, 'tCO₂/万m³', 'IPCC 2006', '2024-01-15', 'active'),
  (4, '电力（华东电网）', '购入能源', 0.7035, 'tCO₂/MWh', '国家发改委 2024', '2024-03-01', 'active'),
  (5, '电力（南方电网）', '购入能源', 0.581, 'tCO₂/MWh', '国家发改委 2024', '2024-03-01', 'active'),
  (6, '水泥熟料', '工业过程', 0.538, 'tCO₂/t', '水泥核算指南', '2024-02-10', 'active'),
  (7, '石灰石', '工业过程', 0.4397, 'tCO₂/t', 'IPCC 2006', '2024-02-10', 'active'),
  (8, '外购蒸汽', '购入能源', 0.11, 'tCO₂/GJ', '工业锅炉核算指南', '2024-02-20', 'inactive');

-- 审核机构（前端静态定义，原样导出）
INSERT INTO `audit_organizations` (`id`, `name`, `short_name`, `code`, `contact`, `phone`, `qualification`, `scope`, `status`, `created_at`) VALUES
  ('AUD001', '中环联合认证中心', NULL, 'CEC', '陈审核', '135-0000-5005', 'C9-2024-001', '钢铁、化工、电力、建材', 'active', '2023-01-10'),
  ('AUD002', '中标合信认证有限公司', '中标合信', NULL, '刘监管', '134-0000-6006', 'C9-2024-002', '电力、建材、化工', 'active', '2023-02-15'),
  ('AUD003', '方圆标志认证集团', NULL, NULL, '孙认证', '132-0000-8008', 'C9-2024-003', '钢铁、建材', 'active', '2023-03-20');

-- 系统用户（账号取自 users，密码取自 demoAccounts，演示环境统一为 123456）
INSERT INTO `users` (`username`, `password`, `name`, `role`, `enterprise_id`, `org_id`, `phone`, `status`, `created_at`) VALUES
  ('enterprise01', '123456', '张建国', 'enterprise', 'ENT20240001', NULL, '138-0000-1001', 'active', '2023-03-15'),
  ('enterprise02', '123456', '李明华', 'enterprise', 'ENT20240002', NULL, '139-0000-2002', 'active', '2023-05-22'),
  ('enterprise03', '123456', '王秀英', 'enterprise', 'ENT20240003', NULL, '137-0000-3003', 'active', '2023-07-10'),
  ('enterprise04', '123456', '赵志强', 'enterprise', 'ENT20240004', NULL, '136-0000-4004', 'active', '2023-09-01'),
  ('auditor01', '123456', '陈审核', 'auditor', NULL, 'AUD001', '135-0000-5005', 'active', '2023-03-01'),
  ('auditor02', '123456', '刘监管', 'auditor', NULL, 'AUD002', '134-0000-6006', 'active', '2023-03-01'),
  ('regulator01', '123456', '周主任', 'regulator', NULL, NULL, '131-0000-9009', 'active', '2023-01-01'),
  ('admin', '123456', '系统管理员', 'admin', NULL, NULL, '133-0000-7007', 'active', '2023-01-01');

-- 碳排放数据记录（由前端生成逻辑产出：4 家企业 × 2024 年 12 个月）
INSERT INTO `emission_records` (`id`, `enterprise_id`, `enterprise_name`, `period`, `source_code`, `source_name`, `activity_data`, `activity_unit`, `emission_factor`, `amount`, `unit`, `status`, `fingerprint`, `created_at`, `reporter`) VALUES
  ('EM000001', 'ENT20240001', '华东钢铁制造有限公司', '2024-01', 'S04', '电力消耗', 597.99, 'MWh', 0.581, 347.43, 'tCO₂', 'certified', '0x1bc2c3c442cb267217a59f7291edd8d81fc421ebc5c660e03c1a07c4705da1be', '2024-01-15 08:34:46', '张建国'),
  ('EM000002', 'ENT20240001', '华东钢铁制造有限公司', '2024-02', 'S04', '电力消耗', 419.66, 'MWh', 0.581, 243.82, 'tCO₂', 'certified', '0x6acfd7633aeec1270472db3d77fa8a054f23cc8a81583adc27b8c6fb6b3ea7ca', '2024-02-15 23:39:31', '张建国'),
  ('EM000003', 'ENT20240001', '华东钢铁制造有限公司', '2024-03', 'S01', '燃煤锅炉', 430.48, 't煤', 2.66, 1145.08, 'tCO₂', 'certified', '0xa61907a3627e8dff4f57795540dca668d7cb13ca77af093fd7474f906da1bee8', '2024-03-15 14:13:12', '张建国'),
  ('EM000004', 'ENT20240001', '华东钢铁制造有限公司', '2024-04', 'S05', '工业过程排放', 571.24, 't产品', 0.86, 491.27, 'tCO₂', 'certified', '0x2e7820a5bf493de629e481fa286083e213c158048fe6b526738478058bc2a7e3', '2024-04-15 01:48:02', '张建国'),
  ('EM000005', 'ENT20240001', '华东钢铁制造有限公司', '2024-05', 'S04', '电力消耗', 485.03, 'MWh', 0.581, 281.8, 'tCO₂', 'certified', '0x158510a6a7a7db9a62e6e8500cf1b2d318bc7f9b2c4047962fcd9f5c6a54df60', '2024-05-15 22:17:37', '张建国'),
  ('EM000006', 'ENT20240001', '华东钢铁制造有限公司', '2024-06', 'S05', '工业过程排放', 485.47, 't产品', 0.86, 417.5, 'tCO₂', 'certified', '0x9bece588ec4cacd510e3d49f6414edfabfa122ed70a9dc6f5619f336ee501596', '2024-06-15 22:46:58', '张建国'),
  ('EM000007', 'ENT20240001', '华东钢铁制造有限公司', '2024-07', 'S03', '柴油消耗', 428.87, 't柴油', 3.09, 1325.21, 'tCO₂', 'certified', '0x0a5d3c0fe6505d15163d845a6a09204d32c5595e8aeadbf62b9d2415f8605bcb', '2024-07-15 00:54:41', '张建国'),
  ('EM000008', 'ENT20240001', '华东钢铁制造有限公司', '2024-08', 'S04', '电力消耗', 466.27, 'MWh', 0.581, 270.9, 'tCO₂', 'rejected', '0x656cd6d1cf14170dbc4d4f0e5f7b6ff46db8ec71361380c72b7ee414a25bc0f9', '2024-08-15 00:30:25', '张建国'),
  ('EM000009', 'ENT20240001', '华东钢铁制造有限公司', '2024-09', 'S01', '燃煤锅炉', 395.57, 't煤', 2.66, 1052.22, 'tCO₂', 'pending', '0x0d89a4e7002762f0a2bcad7dddc6fa3273214f007413f96ece4f9dac2fb10f79', '2024-09-15 01:14:19', '张建国'),
  ('EM000010', 'ENT20240001', '华东钢铁制造有限公司', '2024-10', 'S01', '燃煤锅炉', 513.79, 't煤', 2.66, 1366.68, 'tCO₂', 'rejected', '0x5e0ce520f8565e1ec2c14340790ac09fefd9a48cf95c77ab5a4603d6f561e238', '2024-10-15 08:44:36', '张建国'),
  ('EM000011', 'ENT20240001', '华东钢铁制造有限公司', '2024-11', 'S03', '柴油消耗', 372.71, 't柴油', 3.09, 1151.67, 'tCO₂', 'certified', '0x8e1a0e4255df10995631f651a38d6f3a4feb06c033d4458c7603d644df08c4a7', '2024-11-15 08:54:24', '张建国'),
  ('EM000012', 'ENT20240001', '华东钢铁制造有限公司', '2024-12', 'S02', '天然气燃烧', 460.08, '万m³', 2.16, 993.77, 'tCO₂', 'certified', '0x162bf45e9882cdcfced59018f212cf1029e8fa572aaebd58cfa3769a14c615ff', '2024-12-15 15:05:11', '张建国'),
  ('EM000013', 'ENT20240002', '绿能化工集团', '2024-01', 'S01', '燃煤锅炉', 544.66, 't煤', 2.66, 1448.8, 'tCO₂', 'certified', '0xdf5ff9de765eb96a4bc70a3c15545f69487f3c234db86a5b28c8225d68c70f98', '2024-01-15 22:08:29', '李明华'),
  ('EM000014', 'ENT20240002', '绿能化工集团', '2024-02', 'S01', '燃煤锅炉', 421.92, 't煤', 2.66, 1122.31, 'tCO₂', 'certified', '0x724ea43f023bd290f7c25590d900e2a5f3168edc6eed858956ede5b5d7653eb9', '2024-02-15 01:02:42', '李明华'),
  ('EM000015', 'ENT20240002', '绿能化工集团', '2024-03', 'S05', '工业过程排放', 520.94, 't产品', 0.86, 448.01, 'tCO₂', 'certified', '0x026d9dd09507a423fe207217641f3c83f035562d743b6d2d17676ba8c75da326', '2024-03-15 16:46:00', '李明华'),
  ('EM000016', 'ENT20240002', '绿能化工集团', '2024-04', 'S01', '燃煤锅炉', 498, 't煤', 2.66, 1324.68, 'tCO₂', 'certified', '0x2d0039b826d2448ee3207f38b6e5dec1a14dfa844fb85b8808240032765365fc', '2024-04-15 17:22:02', '李明华'),
  ('EM000017', 'ENT20240002', '绿能化工集团', '2024-05', 'S05', '工业过程排放', 551.41, 't产品', 0.86, 474.21, 'tCO₂', 'certified', '0xc5ced513e1f3f9ab124121f0724ea9e0dd7e46146ccefc96e7236022e09ef355', '2024-05-15 20:50:54', '李明华'),
  ('EM000018', 'ENT20240002', '绿能化工集团', '2024-06', 'S05', '工业过程排放', 579.56, 't产品', 0.86, 498.42, 'tCO₂', 'certified', '0xae429844f6b9e34315bf0beb2991ba1100ac92fb8058878ed746002c6f1d42d6', '2024-06-15 16:04:47', '李明华'),
  ('EM000019', 'ENT20240002', '绿能化工集团', '2024-07', 'S01', '燃煤锅炉', 554.93, 't煤', 2.66, 1476.11, 'tCO₂', 'certified', '0xf7d67a9bf5c64c0c34fc310e34baf258fcf24fbcdf6e19678d86f6a64b2847f4', '2024-07-15 04:27:05', '李明华'),
  ('EM000020', 'ENT20240002', '绿能化工集团', '2024-08', 'S03', '柴油消耗', 488.88, 't柴油', 3.09, 1510.64, 'tCO₂', 'pending', '0x7102596460b1c5777a547cffb85f7c6410e4429df1b66725b3c667bcdf83e7d2', '2024-08-15 19:47:06', '李明华'),
  ('EM000021', 'ENT20240002', '绿能化工集团', '2024-09', 'S05', '工业过程排放', 537.15, 't产品', 0.86, 461.95, 'tCO₂', 'certified', '0x194ebba71b6d4c515cfadaa6733fb6faa0aae547850c36e3b1b1d5b1fd3ba4cf', '2024-09-15 19:48:21', '李明华'),
  ('EM000022', 'ENT20240002', '绿能化工集团', '2024-10', 'S03', '柴油消耗', 438.28, 't柴油', 3.09, 1354.29, 'tCO₂', 'certified', '0xddb21985620a2fe8403ccf0ea710ef57d0a2ac2488c53bde9ff580181bf49bc9', '2024-10-15 03:00:09', '李明华'),
  ('EM000023', 'ENT20240002', '绿能化工集团', '2024-11', 'S01', '燃煤锅炉', 370.32, 't煤', 2.66, 985.05, 'tCO₂', 'pending', '0x538dcf0774b87fa86cfc0235edd7dbd31124557a53ff309b7572f37839a4bc28', '2024-11-15 11:45:54', '李明华'),
  ('EM000024', 'ENT20240002', '绿能化工集团', '2024-12', 'S01', '燃煤锅炉', 503.99, 't煤', 2.66, 1340.61, 'tCO₂', 'pending', '0xaf0df5fb68ecb7c2aa19246af19dbee80342298c05fa841084f5826a0608d059', '2024-12-15 20:40:37', '李明华'),
  ('EM000025', 'ENT20240003', '南方水泥股份有限公司', '2024-01', 'S01', '燃煤锅炉', 555.04, 't煤', 2.66, 1476.41, 'tCO₂', 'pending', '0x1f1384b782093220c324520e46d2769b1ecbcfff2eb0b0c66fccb0826fdd4f56', '2024-01-15 12:06:36', '王秀英'),
  ('EM000026', 'ENT20240003', '南方水泥股份有限公司', '2024-02', 'S05', '工业过程排放', 591.39, 't产品', 0.86, 508.6, 'tCO₂', 'certified', '0x06fc36eef0e17a0588027a0e9308fc37372e766c2b6ca8a972b702fb783f6ca1', '2024-02-15 17:16:07', '王秀英'),
  ('EM000027', 'ENT20240003', '南方水泥股份有限公司', '2024-03', 'S01', '燃煤锅炉', 453.94, 't煤', 2.66, 1207.48, 'tCO₂', 'pending', '0x09513acf63655045ad4aad80235bcfb2a6c6d13d580c605e324887826e00049e', '2024-03-15 00:25:37', '王秀英'),
  ('EM000028', 'ENT20240003', '南方水泥股份有限公司', '2024-04', 'S01', '燃煤锅炉', 615.04, 't煤', 2.66, 1636.01, 'tCO₂', 'certified', '0x59b3033435d4f2d457b5c688606cf941eaa503943476482df7571d567e88340c', '2024-04-15 13:21:03', '王秀英'),
  ('EM000029', 'ENT20240003', '南方水泥股份有限公司', '2024-05', 'S05', '工业过程排放', 645.42, 't产品', 0.86, 555.06, 'tCO₂', 'certified', '0xddb398a87f248153c28b638af2416b878f23a3b9c24cf207e9980f06186a6042', '2024-05-15 21:59:22', '王秀英'),
  ('EM000030', 'ENT20240003', '南方水泥股份有限公司', '2024-06', 'S04', '电力消耗', 541.77, 'MWh', 0.581, 314.77, 'tCO₂', 'certified', '0xff0a5302ef0e4e772381d5216dd7f8fb7ee20e77c7b846219d85c024b37307c7', '2024-06-15 18:56:23', '王秀英'),
  ('EM000031', 'ENT20240003', '南方水泥股份有限公司', '2024-07', 'S04', '电力消耗', 480.15, 'MWh', 0.581, 278.97, 'tCO₂', 'certified', '0x3431f449eb6662be6df5b9c1a5f2a7847be2f820111779a8eb0d339529552433', '2024-07-15 18:57:52', '王秀英'),
  ('EM000032', 'ENT20240003', '南方水泥股份有限公司', '2024-08', 'S01', '燃煤锅炉', 638.82, 't煤', 2.66, 1699.26, 'tCO₂', 'certified', '0x28832064f8ab0f1c3ea972a8fe7fcfcc929b14b35fa6f3dae68aa2d978029c90', '2024-08-15 15:54:02', '王秀英'),
  ('EM000033', 'ENT20240003', '南方水泥股份有限公司', '2024-09', 'S04', '电力消耗', 569.76, 'MWh', 0.581, 331.03, 'tCO₂', 'certified', '0x3d6a0f13680dde3b6eb925eda21bdfb9d226deac1af29ba1e0b75e3939353a8d', '2024-09-15 17:03:13', '王秀英'),
  ('EM000034', 'ENT20240003', '南方水泥股份有限公司', '2024-10', 'S05', '工业过程排放', 479.22, 't产品', 0.86, 412.13, 'tCO₂', 'certified', '0x015eb18252c72262fd6a52720e7fae9e961ebb4ad7804a8b74984d4db5e5775b', '2024-10-15 02:35:36', '王秀英'),
  ('EM000035', 'ENT20240003', '南方水泥股份有限公司', '2024-11', 'S01', '燃煤锅炉', 613.91, 't煤', 2.66, 1633, 'tCO₂', 'certified', '0x6f30270d62b48c5ad887889673da89c79c695f6659f7fc439f59cef282e121d4', '2024-11-15 03:32:31', '王秀英'),
  ('EM000036', 'ENT20240003', '南方水泥股份有限公司', '2024-12', 'S02', '天然气燃烧', 624.71, '万m³', 2.16, 1349.37, 'tCO₂', 'certified', '0x2ba2940b65427a8c9f669c59c509d5c52f4ae31f040ec99b54600987d474e98f', '2024-12-15 22:38:02', '王秀英'),
  ('EM000037', 'ENT20240004', '东方电力集团', '2024-01', 'S03', '柴油消耗', 523.26, 't柴油', 3.09, 1616.87, 'tCO₂', 'certified', '0x47c626e0c4c5f0a863e469c97427d531ec321a32229cc8a61b6405a70384d67f', '2024-01-15 03:36:30', '赵志强'),
  ('EM000038', 'ENT20240004', '东方电力集团', '2024-02', 'S03', '柴油消耗', 519.38, 't柴油', 3.09, 1604.88, 'tCO₂', 'rejected', '0x2fa818c97a0ddd15a28be9a659a60bfd27ba8c519470c84bfdcf5a81c9e62132', '2024-02-15 01:04:31', '赵志强'),
  ('EM000039', 'ENT20240004', '东方电力集团', '2024-03', 'S05', '工业过程排放', 482.99, 't产品', 0.86, 415.37, 'tCO₂', 'rejected', '0x0ec41508f42f30b1a0b3fac3bdcd126da2e2f3ec9a82de0907d4953d60c4f9de', '2024-03-15 17:57:20', '赵志强'),
  ('EM000040', 'ENT20240004', '东方电力集团', '2024-04', 'S01', '燃煤锅炉', 388.12, 't煤', 2.66, 1032.4, 'tCO₂', 'certified', '0x9679ef2043279444f193109e92021bd68bf5c39af92cf4dd3b16b8631260d59c', '2024-04-15 04:23:33', '赵志强'),
  ('EM000041', 'ENT20240004', '东方电力集团', '2024-05', 'S04', '电力消耗', 454.48, 'MWh', 0.581, 264.05, 'tCO₂', 'rejected', '0x6ecf828e7f154fc7b42c04abc72775026e879d2e274dc8e30354db79775a5a99', '2024-05-15 22:03:18', '赵志强'),
  ('EM000042', 'ENT20240004', '东方电力集团', '2024-06', 'S05', '工业过程排放', 553.36, 't产品', 0.86, 475.89, 'tCO₂', 'pending', '0xf10113e10cd9b59ffdb2ff3cd1fccd32c7ac760a93cb6f91d1db946713c814dc', '2024-06-15 11:21:47', '赵志强'),
  ('EM000043', 'ENT20240004', '东方电力集团', '2024-07', 'S02', '天然气燃烧', 517.07, '万m³', 2.16, 1116.87, 'tCO₂', 'certified', '0xdaed94638de1a98397d7e95716e5791b2387a3c84c83db004da486b8ac77337f', '2024-07-15 06:38:58', '赵志强'),
  ('EM000044', 'ENT20240004', '东方电力集团', '2024-08', 'S03', '柴油消耗', 628.11, 't柴油', 3.09, 1940.86, 'tCO₂', 'rejected', '0x0de83662f13dd0e31f6bd6d3954d82cfd35887b7105b7f42fe9263d628554b22', '2024-08-15 18:42:13', '赵志强'),
  ('EM000045', 'ENT20240004', '东方电力集团', '2024-09', 'S02', '天然气燃烧', 647.82, '万m³', 2.16, 1399.29, 'tCO₂', 'certified', '0xbdb3aa230271b98a9247c336e1f39dbcf1cfbddf45cce76a293b8430f77aa2e2', '2024-09-15 16:17:09', '赵志强'),
  ('EM000046', 'ENT20240004', '东方电力集团', '2024-10', 'S02', '天然气燃烧', 643.3, '万m³', 2.16, 1389.53, 'tCO₂', 'rejected', '0xa25953304b73d43582c9a6961b2fd84f717fa602d6c1fb60bd72c142250a9b3e', '2024-10-15 08:02:48', '赵志强'),
  ('EM000047', 'ENT20240004', '东方电力集团', '2024-11', 'S05', '工业过程排放', 649.32, 't产品', 0.86, 558.42, 'tCO₂', 'certified', '0x573c30f6442d50144cc02043416f899daf5acd99b44f6b5b09abc83b644acb0c', '2024-11-15 17:16:14', '赵志强'),
  ('EM000048', 'ENT20240004', '东方电力集团', '2024-12', 'S01', '燃煤锅炉', 480.24, 't煤', 2.66, 1277.44, 'tCO₂', 'pending', '0x494ba0f4d3c1a3db0b3c43cc5e9a49cad5de54f37388690a662c854988274d91', '2024-12-15 11:03:45', '赵志强');

-- 报告提交记录（与排放记录一一对应）
INSERT INTO `report_records` (`id`, `emission_id`, `enterprise_id`, `enterprise_name`, `period`, `source_name`, `amount`, `status`, `fingerprint`, `submitter`, `submitted_at`, `auditor`, `audited_at`, `audit_opinion`) VALUES
  ('RPT000001', 'EM000001', 'ENT20240001', '华东钢铁制造有限公司', '2024-01', '电力消耗', 347.43, 'certified', '0x1bc2c3c442cb267217a59f7291edd8d81fc421ebc5c660e03c1a07c4705da1be', '张建国', '2024-01-15 08:34:46', '陈审核', '2024-01-15 08:34:46', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000002', 'EM000002', 'ENT20240001', '华东钢铁制造有限公司', '2024-02', '电力消耗', 243.82, 'certified', '0x6acfd7633aeec1270472db3d77fa8a054f23cc8a81583adc27b8c6fb6b3ea7ca', '张建国', '2024-02-15 23:39:31', '刘监管', '2024-02-15 23:39:31', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000003', 'EM000003', 'ENT20240001', '华东钢铁制造有限公司', '2024-03', '燃煤锅炉', 1145.08, 'certified', '0xa61907a3627e8dff4f57795540dca668d7cb13ca77af093fd7474f906da1bee8', '张建国', '2024-03-15 14:13:12', '陈审核', '2024-03-15 14:13:12', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000004', 'EM000004', 'ENT20240001', '华东钢铁制造有限公司', '2024-04', '工业过程排放', 491.27, 'certified', '0x2e7820a5bf493de629e481fa286083e213c158048fe6b526738478058bc2a7e3', '张建国', '2024-04-15 01:48:02', '刘监管', '2024-04-15 01:48:02', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000005', 'EM000005', 'ENT20240001', '华东钢铁制造有限公司', '2024-05', '电力消耗', 281.8, 'certified', '0x158510a6a7a7db9a62e6e8500cf1b2d318bc7f9b2c4047962fcd9f5c6a54df60', '张建国', '2024-05-15 22:17:37', '陈审核', '2024-05-15 22:17:37', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000006', 'EM000006', 'ENT20240001', '华东钢铁制造有限公司', '2024-06', '工业过程排放', 417.5, 'certified', '0x9bece588ec4cacd510e3d49f6414edfabfa122ed70a9dc6f5619f336ee501596', '张建国', '2024-06-15 22:46:58', '刘监管', '2024-06-15 22:46:58', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000007', 'EM000007', 'ENT20240001', '华东钢铁制造有限公司', '2024-07', '柴油消耗', 1325.21, 'certified', '0x0a5d3c0fe6505d15163d845a6a09204d32c5595e8aeadbf62b9d2415f8605bcb', '张建国', '2024-07-15 00:54:41', '陈审核', '2024-07-15 00:54:41', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000008', 'EM000008', 'ENT20240001', '华东钢铁制造有限公司', '2024-08', '电力消耗', 270.9, 'rejected', '0x656cd6d1cf14170dbc4d4f0e5f7b6ff46db8ec71361380c72b7ee414a25bc0f9', '张建国', '2024-08-15 00:30:25', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000009', 'EM000009', 'ENT20240001', '华东钢铁制造有限公司', '2024-09', '燃煤锅炉', 1052.22, 'pending', '0x0d89a4e7002762f0a2bcad7dddc6fa3273214f007413f96ece4f9dac2fb10f79', '张建国', '2024-09-15 01:14:19', NULL, NULL, NULL),
  ('RPT000010', 'EM000010', 'ENT20240001', '华东钢铁制造有限公司', '2024-10', '燃煤锅炉', 1366.68, 'rejected', '0x5e0ce520f8565e1ec2c14340790ac09fefd9a48cf95c77ab5a4603d6f561e238', '张建国', '2024-10-15 08:44:36', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000011', 'EM000011', 'ENT20240001', '华东钢铁制造有限公司', '2024-11', '柴油消耗', 1151.67, 'certified', '0x8e1a0e4255df10995631f651a38d6f3a4feb06c033d4458c7603d644df08c4a7', '张建国', '2024-11-15 08:54:24', '陈审核', '2024-11-15 08:54:24', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000012', 'EM000012', 'ENT20240001', '华东钢铁制造有限公司', '2024-12', '天然气燃烧', 993.77, 'certified', '0x162bf45e9882cdcfced59018f212cf1029e8fa572aaebd58cfa3769a14c615ff', '张建国', '2024-12-15 15:05:11', '刘监管', '2024-12-15 15:05:11', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000013', 'EM000013', 'ENT20240002', '绿能化工集团', '2024-01', '燃煤锅炉', 1448.8, 'certified', '0xdf5ff9de765eb96a4bc70a3c15545f69487f3c234db86a5b28c8225d68c70f98', '李明华', '2024-01-15 22:08:29', '陈审核', '2024-01-15 22:08:29', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000014', 'EM000014', 'ENT20240002', '绿能化工集团', '2024-02', '燃煤锅炉', 1122.31, 'certified', '0x724ea43f023bd290f7c25590d900e2a5f3168edc6eed858956ede5b5d7653eb9', '李明华', '2024-02-15 01:02:42', '刘监管', '2024-02-15 01:02:42', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000015', 'EM000015', 'ENT20240002', '绿能化工集团', '2024-03', '工业过程排放', 448.01, 'certified', '0x026d9dd09507a423fe207217641f3c83f035562d743b6d2d17676ba8c75da326', '李明华', '2024-03-15 16:46:00', '陈审核', '2024-03-15 16:46:00', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000016', 'EM000016', 'ENT20240002', '绿能化工集团', '2024-04', '燃煤锅炉', 1324.68, 'certified', '0x2d0039b826d2448ee3207f38b6e5dec1a14dfa844fb85b8808240032765365fc', '李明华', '2024-04-15 17:22:02', '刘监管', '2024-04-15 17:22:02', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000017', 'EM000017', 'ENT20240002', '绿能化工集团', '2024-05', '工业过程排放', 474.21, 'certified', '0xc5ced513e1f3f9ab124121f0724ea9e0dd7e46146ccefc96e7236022e09ef355', '李明华', '2024-05-15 20:50:54', '陈审核', '2024-05-15 20:50:54', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000018', 'EM000018', 'ENT20240002', '绿能化工集团', '2024-06', '工业过程排放', 498.42, 'certified', '0xae429844f6b9e34315bf0beb2991ba1100ac92fb8058878ed746002c6f1d42d6', '李明华', '2024-06-15 16:04:47', '刘监管', '2024-06-15 16:04:47', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000019', 'EM000019', 'ENT20240002', '绿能化工集团', '2024-07', '燃煤锅炉', 1476.11, 'certified', '0xf7d67a9bf5c64c0c34fc310e34baf258fcf24fbcdf6e19678d86f6a64b2847f4', '李明华', '2024-07-15 04:27:05', '陈审核', '2024-07-15 04:27:05', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000020', 'EM000020', 'ENT20240002', '绿能化工集团', '2024-08', '柴油消耗', 1510.64, 'pending', '0x7102596460b1c5777a547cffb85f7c6410e4429df1b66725b3c667bcdf83e7d2', '李明华', '2024-08-15 19:47:06', NULL, NULL, NULL),
  ('RPT000021', 'EM000021', 'ENT20240002', '绿能化工集团', '2024-09', '工业过程排放', 461.95, 'certified', '0x194ebba71b6d4c515cfadaa6733fb6faa0aae547850c36e3b1b1d5b1fd3ba4cf', '李明华', '2024-09-15 19:48:21', '陈审核', '2024-09-15 19:48:21', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000022', 'EM000022', 'ENT20240002', '绿能化工集团', '2024-10', '柴油消耗', 1354.29, 'certified', '0xddb21985620a2fe8403ccf0ea710ef57d0a2ac2488c53bde9ff580181bf49bc9', '李明华', '2024-10-15 03:00:09', '刘监管', '2024-10-15 03:00:09', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000023', 'EM000023', 'ENT20240002', '绿能化工集团', '2024-11', '燃煤锅炉', 985.05, 'pending', '0x538dcf0774b87fa86cfc0235edd7dbd31124557a53ff309b7572f37839a4bc28', '李明华', '2024-11-15 11:45:54', NULL, NULL, NULL),
  ('RPT000024', 'EM000024', 'ENT20240002', '绿能化工集团', '2024-12', '燃煤锅炉', 1340.61, 'pending', '0xaf0df5fb68ecb7c2aa19246af19dbee80342298c05fa841084f5826a0608d059', '李明华', '2024-12-15 20:40:37', NULL, NULL, NULL),
  ('RPT000025', 'EM000025', 'ENT20240003', '南方水泥股份有限公司', '2024-01', '燃煤锅炉', 1476.41, 'pending', '0x1f1384b782093220c324520e46d2769b1ecbcfff2eb0b0c66fccb0826fdd4f56', '王秀英', '2024-01-15 12:06:36', NULL, NULL, NULL),
  ('RPT000026', 'EM000026', 'ENT20240003', '南方水泥股份有限公司', '2024-02', '工业过程排放', 508.6, 'certified', '0x06fc36eef0e17a0588027a0e9308fc37372e766c2b6ca8a972b702fb783f6ca1', '王秀英', '2024-02-15 17:16:07', '刘监管', '2024-02-15 17:16:07', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000027', 'EM000027', 'ENT20240003', '南方水泥股份有限公司', '2024-03', '燃煤锅炉', 1207.48, 'pending', '0x09513acf63655045ad4aad80235bcfb2a6c6d13d580c605e324887826e00049e', '王秀英', '2024-03-15 00:25:37', NULL, NULL, NULL),
  ('RPT000028', 'EM000028', 'ENT20240003', '南方水泥股份有限公司', '2024-04', '燃煤锅炉', 1636.01, 'certified', '0x59b3033435d4f2d457b5c688606cf941eaa503943476482df7571d567e88340c', '王秀英', '2024-04-15 13:21:03', '刘监管', '2024-04-15 13:21:03', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000029', 'EM000029', 'ENT20240003', '南方水泥股份有限公司', '2024-05', '工业过程排放', 555.06, 'certified', '0xddb398a87f248153c28b638af2416b878f23a3b9c24cf207e9980f06186a6042', '王秀英', '2024-05-15 21:59:22', '陈审核', '2024-05-15 21:59:22', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000030', 'EM000030', 'ENT20240003', '南方水泥股份有限公司', '2024-06', '电力消耗', 314.77, 'certified', '0xff0a5302ef0e4e772381d5216dd7f8fb7ee20e77c7b846219d85c024b37307c7', '王秀英', '2024-06-15 18:56:23', '刘监管', '2024-06-15 18:56:23', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000031', 'EM000031', 'ENT20240003', '南方水泥股份有限公司', '2024-07', '电力消耗', 278.97, 'certified', '0x3431f449eb6662be6df5b9c1a5f2a7847be2f820111779a8eb0d339529552433', '王秀英', '2024-07-15 18:57:52', '陈审核', '2024-07-15 18:57:52', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000032', 'EM000032', 'ENT20240003', '南方水泥股份有限公司', '2024-08', '燃煤锅炉', 1699.26, 'certified', '0x28832064f8ab0f1c3ea972a8fe7fcfcc929b14b35fa6f3dae68aa2d978029c90', '王秀英', '2024-08-15 15:54:02', '刘监管', '2024-08-15 15:54:02', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000033', 'EM000033', 'ENT20240003', '南方水泥股份有限公司', '2024-09', '电力消耗', 331.03, 'certified', '0x3d6a0f13680dde3b6eb925eda21bdfb9d226deac1af29ba1e0b75e3939353a8d', '王秀英', '2024-09-15 17:03:13', '陈审核', '2024-09-15 17:03:13', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000034', 'EM000034', 'ENT20240003', '南方水泥股份有限公司', '2024-10', '工业过程排放', 412.13, 'certified', '0x015eb18252c72262fd6a52720e7fae9e961ebb4ad7804a8b74984d4db5e5775b', '王秀英', '2024-10-15 02:35:36', '刘监管', '2024-10-15 02:35:36', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000035', 'EM000035', 'ENT20240003', '南方水泥股份有限公司', '2024-11', '燃煤锅炉', 1633, 'certified', '0x6f30270d62b48c5ad887889673da89c79c695f6659f7fc439f59cef282e121d4', '王秀英', '2024-11-15 03:32:31', '陈审核', '2024-11-15 03:32:31', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000036', 'EM000036', 'ENT20240003', '南方水泥股份有限公司', '2024-12', '天然气燃烧', 1349.37, 'certified', '0x2ba2940b65427a8c9f669c59c509d5c52f4ae31f040ec99b54600987d474e98f', '王秀英', '2024-12-15 22:38:02', '刘监管', '2024-12-15 22:38:02', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000037', 'EM000037', 'ENT20240004', '东方电力集团', '2024-01', '柴油消耗', 1616.87, 'certified', '0x47c626e0c4c5f0a863e469c97427d531ec321a32229cc8a61b6405a70384d67f', '赵志强', '2024-01-15 03:36:30', '陈审核', '2024-01-15 03:36:30', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000038', 'EM000038', 'ENT20240004', '东方电力集团', '2024-02', '柴油消耗', 1604.88, 'rejected', '0x2fa818c97a0ddd15a28be9a659a60bfd27ba8c519470c84bfdcf5a81c9e62132', '赵志强', '2024-02-15 01:04:31', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000039', 'EM000039', 'ENT20240004', '东方电力集团', '2024-03', '工业过程排放', 415.37, 'rejected', '0x0ec41508f42f30b1a0b3fac3bdcd126da2e2f3ec9a82de0907d4953d60c4f9de', '赵志强', '2024-03-15 17:57:20', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000040', 'EM000040', 'ENT20240004', '东方电力集团', '2024-04', '燃煤锅炉', 1032.4, 'certified', '0x9679ef2043279444f193109e92021bd68bf5c39af92cf4dd3b16b8631260d59c', '赵志强', '2024-04-15 04:23:33', '刘监管', '2024-04-15 04:23:33', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000041', 'EM000041', 'ENT20240004', '东方电力集团', '2024-05', '电力消耗', 264.05, 'rejected', '0x6ecf828e7f154fc7b42c04abc72775026e879d2e274dc8e30354db79775a5a99', '赵志强', '2024-05-15 22:03:18', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000042', 'EM000042', 'ENT20240004', '东方电力集团', '2024-06', '工业过程排放', 475.89, 'pending', '0xf10113e10cd9b59ffdb2ff3cd1fccd32c7ac760a93cb6f91d1db946713c814dc', '赵志强', '2024-06-15 11:21:47', NULL, NULL, NULL),
  ('RPT000043', 'EM000043', 'ENT20240004', '东方电力集团', '2024-07', '天然气燃烧', 1116.87, 'certified', '0xdaed94638de1a98397d7e95716e5791b2387a3c84c83db004da486b8ac77337f', '赵志强', '2024-07-15 06:38:58', '陈审核', '2026-09-28 09:20:15', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000044', 'EM000044', 'ENT20240004', '东方电力集团', '2024-08', '柴油消耗', 1940.86, 'rejected', '0x0de83662f13dd0e31f6bd6d3954d82cfd35887b7105b7f42fe9263d628554b22', '赵志强', '2024-08-15 18:42:13', NULL, NULL, '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000045', 'EM000045', 'ENT20240004', '东方电力集团', '2024-09', '天然气燃烧', 1399.29, 'certified', '0xbdb3aa230271b98a9247c336e1f39dbcf1cfbddf45cce76a293b8430f77aa2e2', '赵志强', '2024-09-15 16:17:09', '陈审核', '2026-09-28 10:45:32', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000046', 'EM000046', 'ENT20240004', '东方电力集团', '2024-10', '天然气燃烧', 1389.53, 'rejected', '0xa25953304b73d43582c9a6961b2fd84f717fa602d6c1fb60bd72c142250a9b3e', '赵志强', '2024-10-15 08:02:48', '陈审核', '2026-09-28 16:30:05', '活动数据凭证缺失，请补充后重新提交。'),
  ('RPT000047', 'EM000047', 'ENT20240004', '东方电力集团', '2024-11', '工业过程排放', 558.42, 'certified', '0x573c30f6442d50144cc02043416f899daf5acd99b44f6b5b09abc83b644acb0c', '赵志强', '2024-11-15 17:16:14', '陈审核', '2026-09-28 14:08:47', '数据真实有效，排放因子使用符合核算指南，予以认证。'),
  ('RPT000048', 'EM000048', 'ENT20240004', '东方电力集团', '2024-12', '燃煤锅炉', 1277.44, 'pending', '0x494ba0f4d3c1a3db0b3c43cc5e9a49cad5de54f37388690a662c854988274d91', '赵志强', '2024-12-15 11:03:45', NULL, NULL, NULL);

-- 认证记录（由已认证的排放记录派生，含真实 SHA-256 交易哈希）
INSERT INTO `certification_records` (`id`, `emission_id`, `enterprise_id`, `enterprise_name`, `period`, `amount`, `fingerprint`, `tx_hash`, `block_index`, `auditor`, `audit_opinion`, `certified_at`, `status`, `revoke_reason`, `revoked_at`) VALUES
  ('CERT000001', 'EM000001', 'ENT20240001', '华东钢铁制造有限公司', '2024-01', 347.43, '0x1bc2c3c442cb267217a59f7291edd8d81fc421ebc5c660e03c1a07c4705da1be', '0x40ef1f7cb724092abc8dc7d4b4e7db4b9523e2230047a7993fd2e4f773f09129', 1, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-28 06:53:30', 'valid', NULL, NULL),
  ('CERT000002', 'EM000002', 'ENT20240001', '华东钢铁制造有限公司', '2024-02', 243.82, '0x6acfd7633aeec1270472db3d77fa8a054f23cc8a81583adc27b8c6fb6b3ea7ca', '0x5639f7116ee47bc4ecf368c6dadcce0f2c5eec50aff98de8a3f2db92f73fb0d1', 1, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-28 01:53:30', 'valid', NULL, NULL),
  ('CERT000003', 'EM000003', 'ENT20240001', '华东钢铁制造有限公司', '2024-03', 1145.08, '0xa61907a3627e8dff4f57795540dca668d7cb13ca77af093fd7474f906da1bee8', '0x0db64d7903a6682f6ec8dacdcd36140372769ba8fba6b644b353d12eea751ecd', 1, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-27 20:53:30', 'valid', NULL, NULL),
  ('CERT000004', 'EM000004', 'ENT20240001', '华东钢铁制造有限公司', '2024-04', 491.27, '0x2e7820a5bf493de629e481fa286083e213c158048fe6b526738478058bc2a7e3', '0x6d40212a76864c67a24caa57a4ea7f555bc775b764c9da0691081da7212134d5', 1, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-27 15:53:30', 'valid', NULL, NULL),
  ('CERT000005', 'EM000005', 'ENT20240001', '华东钢铁制造有限公司', '2024-05', 281.8, '0x158510a6a7a7db9a62e6e8500cf1b2d318bc7f9b2c4047962fcd9f5c6a54df60', '0x20720050bb7d91658d37d5f576c72c82c75d0ed62f38274f1b65752002e9e7eb', 2, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-27 10:53:30', 'valid', NULL, NULL),
  ('CERT000006', 'EM000006', 'ENT20240001', '华东钢铁制造有限公司', '2024-06', 417.5, '0x9bece588ec4cacd510e3d49f6414edfabfa122ed70a9dc6f5619f336ee501596', '0xa669f7d87f4e9973b7bb5ef9837650dac316347942e442b41f7eaa8d3fbfb953', 2, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-27 05:53:30', 'valid', NULL, NULL),
  ('CERT000007', 'EM000007', 'ENT20240001', '华东钢铁制造有限公司', '2024-07', 1325.21, '0x0a5d3c0fe6505d15163d845a6a09204d32c5595e8aeadbf62b9d2415f8605bcb', '0xab92aaffca0ddf963b60d8fd62dc45363894e668f7e94f1a92f333d258e67b69', 2, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-27 00:53:30', 'valid', NULL, NULL),
  ('CERT000008', 'EM000011', 'ENT20240001', '华东钢铁制造有限公司', '2024-11', 1151.67, '0x8e1a0e4255df10995631f651a38d6f3a4feb06c033d4458c7603d644df08c4a7', '0x04ab47ad61844c00a6ea5322298175f5897e99efa93e313a81418e62489fd473', 2, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-26 19:53:30', 'valid', NULL, NULL),
  ('CERT000009', 'EM000012', 'ENT20240001', '华东钢铁制造有限公司', '2024-12', 993.77, '0x162bf45e9882cdcfced59018f212cf1029e8fa572aaebd58cfa3769a14c615ff', '0xe4af47f908c587f7b8871db2a883338164613890d32902bcce54155ab72d1ee5', 3, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-26 14:53:30', 'valid', NULL, NULL),
  ('CERT000010', 'EM000013', 'ENT20240002', '绿能化工集团', '2024-01', 1448.8, '0xdf5ff9de765eb96a4bc70a3c15545f69487f3c234db86a5b28c8225d68c70f98', '0xc0803d3e6745314aec0084cecb4e8b3d225434825f32ccc112e247b5daf18441', 3, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-26 09:53:30', 'valid', NULL, NULL),
  ('CERT000011', 'EM000014', 'ENT20240002', '绿能化工集团', '2024-02', 1122.31, '0x724ea43f023bd290f7c25590d900e2a5f3168edc6eed858956ede5b5d7653eb9', '0xde5fe41fe6cc21832c99156e1f651ba6c8163c94bc276a1636ffb794f3ebfe4a', 3, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-26 04:53:30', 'valid', NULL, NULL),
  ('CERT000012', 'EM000015', 'ENT20240002', '绿能化工集团', '2024-03', 448.01, '0x026d9dd09507a423fe207217641f3c83f035562d743b6d2d17676ba8c75da326', '0xbf0f0036625eb3a3602aab27fd95675eadb3709723bc6db5fbecdd60ce3c902b', 3, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-25 23:53:30', 'valid', NULL, NULL),
  ('CERT000013', 'EM000016', 'ENT20240002', '绿能化工集团', '2024-04', 1324.68, '0x2d0039b826d2448ee3207f38b6e5dec1a14dfa844fb85b8808240032765365fc', '0x897aab7fa71c230243f326452a5d1d2521d4b438d1d996582262e9a8db2d497c', 4, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-25 18:53:30', 'valid', NULL, NULL),
  ('CERT000014', 'EM000017', 'ENT20240002', '绿能化工集团', '2024-05', 474.21, '0xc5ced513e1f3f9ab124121f0724ea9e0dd7e46146ccefc96e7236022e09ef355', '0x651a36618af3cf5a52cf0df6f89ff8c3ca661c807955858ccb986f61d1d1530d', 4, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-25 13:53:30', 'valid', NULL, NULL),
  ('CERT000015', 'EM000018', 'ENT20240002', '绿能化工集团', '2024-06', 498.42, '0xae429844f6b9e34315bf0beb2991ba1100ac92fb8058878ed746002c6f1d42d6', '0x50f22d16437bc1bf4e2c76374d9f79f02c7d39ea96838f04e110ccafcae80441', 4, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-25 08:53:30', 'valid', NULL, NULL),
  ('CERT000016', 'EM000019', 'ENT20240002', '绿能化工集团', '2024-07', 1476.11, '0xf7d67a9bf5c64c0c34fc310e34baf258fcf24fbcdf6e19678d86f6a64b2847f4', '0xace52f743be36ffebcc7119d0772314e940cd1e0a0e4b290b6d80fe1cb11983e', 4, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-25 03:53:30', 'valid', NULL, NULL),
  ('CERT000017', 'EM000021', 'ENT20240002', '绿能化工集团', '2024-09', 461.95, '0x194ebba71b6d4c515cfadaa6733fb6faa0aae547850c36e3b1b1d5b1fd3ba4cf', '0xb4d9ba39d1ea1b37b39613151dd06a1a74bdb0a9b0acd7bb4a5fa8ea5c6ff9c2', 5, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-24 22:53:30', 'valid', NULL, NULL),
  ('CERT000018', 'EM000022', 'ENT20240002', '绿能化工集团', '2024-10', 1354.29, '0xddb21985620a2fe8403ccf0ea710ef57d0a2ac2488c53bde9ff580181bf49bc9', '0xe3b730b9c78529e4870ff0cd3be3b61f1ad11ba4b675b7f159dc1b4eb5226325', 5, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-24 17:53:30', 'valid', NULL, NULL),
  ('CERT000019', 'EM000026', 'ENT20240003', '南方水泥股份有限公司', '2024-02', 508.6, '0x06fc36eef0e17a0588027a0e9308fc37372e766c2b6ca8a972b702fb783f6ca1', '0x73ad99ac86622a93a29c3908fd69c88eebbfb6c8cd6b96ad5747026c3a4e281e', 5, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-24 12:53:30', 'valid', NULL, NULL),
  ('CERT000020', 'EM000028', 'ENT20240003', '南方水泥股份有限公司', '2024-04', 1636.01, '0x59b3033435d4f2d457b5c688606cf941eaa503943476482df7571d567e88340c', '0xb8e1791ae5ac6de963c0dc93873307841b33a7ee7d84acd88aafc5afd5587c46', 5, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-24 07:53:30', 'valid', NULL, NULL),
  ('CERT000021', 'EM000029', 'ENT20240003', '南方水泥股份有限公司', '2024-05', 555.06, '0xddb398a87f248153c28b638af2416b878f23a3b9c24cf207e9980f06186a6042', '0x363ff75a71144d3d05e9bc3cd49d94f0691c98ecbc2aa1e68e7db023a05b592f', 6, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-24 02:53:30', 'valid', NULL, NULL),
  ('CERT000022', 'EM000030', 'ENT20240003', '南方水泥股份有限公司', '2024-06', 314.77, '0xff0a5302ef0e4e772381d5216dd7f8fb7ee20e77c7b846219d85c024b37307c7', '0xeb6f559b82a15d4894b15577d17b1717499f05d7a3b6f8cae3d0a6cbd6c20b10', 6, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-23 21:53:30', 'valid', NULL, NULL),
  ('CERT000023', 'EM000031', 'ENT20240003', '南方水泥股份有限公司', '2024-07', 278.97, '0x3431f449eb6662be6df5b9c1a5f2a7847be2f820111779a8eb0d339529552433', '0x0021b1007637abbcc72b920a64459fdf147f15b9abc1b3c9bf476058a57d9cdb', 6, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-23 16:53:30', 'valid', NULL, NULL),
  ('CERT000024', 'EM000032', 'ENT20240003', '南方水泥股份有限公司', '2024-08', 1699.26, '0x28832064f8ab0f1c3ea972a8fe7fcfcc929b14b35fa6f3dae68aa2d978029c90', '0x8fb3d5ea7fce25bb65bb416436aa8fe4a3c31d12b7c84f4489cbbcdadb096fb8', 6, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-23 11:53:30', 'valid', NULL, NULL),
  ('CERT000025', 'EM000033', 'ENT20240003', '南方水泥股份有限公司', '2024-09', 331.03, '0x3d6a0f13680dde3b6eb925eda21bdfb9d226deac1af29ba1e0b75e3939353a8d', '0xa1c32abbae526b27b8502f01e07473ec02b93e4b0261d019f895d0b1b846cfb1', 7, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-23 06:53:30', 'valid', NULL, NULL),
  ('CERT000026', 'EM000034', 'ENT20240003', '南方水泥股份有限公司', '2024-10', 412.13, '0x015eb18252c72262fd6a52720e7fae9e961ebb4ad7804a8b74984d4db5e5775b', '0x1ee6e3c7bf1567bfebc0ccfd7eadabe83d7877ff0c08f7baaeeb46d27e8d7c73', 7, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-23 01:53:30', 'valid', NULL, NULL),
  ('CERT000027', 'EM000035', 'ENT20240003', '南方水泥股份有限公司', '2024-11', 1633, '0x6f30270d62b48c5ad887889673da89c79c695f6659f7fc439f59cef282e121d4', '0xdce83b0d168c22547da2febb4e7d639b59807a6962cb10d3c21fc6e0921c2b7b', 7, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-22 20:53:30', 'valid', NULL, NULL),
  ('CERT000028', 'EM000036', 'ENT20240003', '南方水泥股份有限公司', '2024-12', 1349.37, '0x2ba2940b65427a8c9f669c59c509d5c52f4ae31f040ec99b54600987d474e98f', '0x9b9d848c593b781611f9e1f08b8ab0d8f1ff20fc20b2ac2824756a52dc83966e', 7, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-22 15:53:30', 'valid', NULL, NULL),
  ('CERT000029', 'EM000037', 'ENT20240004', '东方电力集团', '2024-01', 1616.87, '0x47c626e0c4c5f0a863e469c97427d531ec321a32229cc8a61b6405a70384d67f', '0x6b393cf52db32523e40d8eb45ea564dbcd1803ce5d3c8a3e7e7a08c7f257e6b4', 8, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-22 10:53:30', 'valid', NULL, NULL),
  ('CERT000030', 'EM000040', 'ENT20240004', '东方电力集团', '2024-04', 1032.4, '0x9679ef2043279444f193109e92021bd68bf5c39af92cf4dd3b16b8631260d59c', '0xa646f838356ad39b61c0e7814f054e12c4f98e582ae4c19b1516f8f2c973f4a9', 8, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-22 05:53:30', 'valid', NULL, NULL),
  ('CERT000031', 'EM000043', 'ENT20240004', '东方电力集团', '2024-07', 1116.87, '0xdaed94638de1a98397d7e95716e5791b2387a3c84c83db004da486b8ac77337f', '0xa75a53e6513b687c2f06aff6f80b39ed4647b127eb7ba421d35ad4a4074562c3', 8, '陈审核', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-22 00:53:30', 'valid', NULL, NULL),
  ('CERT000032', 'EM000045', 'ENT20240004', '东方电力集团', '2024-09', 1399.29, '0xbdb3aa230271b98a9247c336e1f39dbcf1cfbddf45cce76a293b8430f77aa2e2', '0x04b405c7ff452d0dad1965ae63a776150272a8f7ff14a20e9acb05ae34f15312', 8, '刘监管', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-21 19:53:30', 'valid', NULL, NULL),
  ('CERT000033', 'EM000047', 'ENT20240004', '东方电力集团', '2024-11', 558.42, '0x573c30f6442d50144cc02043416f899daf5acd99b44f6b5b09abc83b644acb0c', '0xc29d3908e94744a9bf9cdccafccf3746441cafda038c67a2391a78be1d66fa92', 9, '周主任', '数据真实有效，排放因子使用符合《企业温室气体排放核算方法与报告指南》，予以认证。', '2026-09-21 14:53:30', 'valid', NULL, NULL);

-- 区块链-区块（含创世区块，PoW 难度 2）
-- 【已下线】仅作历史演示存档；实库无 blocks 表，重建库时应删除本段 INSERT。
INSERT INTO `blocks` (`block_index`, `hash`, `previous_hash`, `timestamp`, `merkle_root`, `nonce`, `miner`, `tx_count`) VALUES
  (0, '0x35139c1cd65d7be9b5aae15d6e6aca224c3b2af3acfa4be7b089a0bdf4be3cfc', '0x0000000000000000000000000000000000000000000000000000000000000000', 1790568410943, '0x12af4cc063812fe6ef52de1b954c8894474d701224ffb1bf9436c3fdbcd2edc1', 0, '监管节点-01', 0),
  (1, '0x0039dab980ca9c6ddf3fce3656cfb9e81e85070c8a1c857c6383bb068ee87d80', '0x35139c1cd65d7be9b5aae15d6e6aca224c3b2af3acfa4be7b089a0bdf4be3cfc', 1790576430943, '0x12af4cc063812fe6ef52de1b954c8894474d701224ffb1bf9436c3fdbcd2edc1', 183, '监管节点-02', 4),
  (2, '0x00c67bc8b416ba081f8505d55f14a453a34c4b68d67d0e917923e60e9021a084', '0x0039dab980ca9c6ddf3fce3656cfb9e81e85070c8a1c857c6383bb068ee87d80', 1790576670945, '0x784749a29784c32336aaf4042a64648538f3ded437e067d36cfcbac8d8454078', 64, '监管节点-03', 4),
  (3, '0x0064b59a1f483b98974eb26f9a10cd55283ed8f9b96fdd8d6d9817869d4c4e30', '0x00c67bc8b416ba081f8505d55f14a453a34c4b68d67d0e917923e60e9021a084', 1790576910946, '0x721ff07aab23d7cd634c4cf1c298a8063e38a3652d88ec4c9c0694c5ebe74925', 146, '监管节点-01', 4),
  (4, '0x00f3b87d4e143ff8f0976f14b87587ace1205209027e589649d5b7e7de391b43', '0x0064b59a1f483b98974eb26f9a10cd55283ed8f9b96fdd8d6d9817869d4c4e30', 1790577150947, '0x528d892a4ae8a61daaec425eaed7cb8c8ada0ee0370fcd9e09e350469c594619', 218, '监管节点-02', 4),
  (5, '0x00f6df71aebd23ea28adbe701ce04ceea500f15a8dc49deaeab6d840056dcf4a', '0x00f3b87d4e143ff8f0976f14b87587ace1205209027e589649d5b7e7de391b43', 1790577390949, '0xafcce26cc9c9d2a875ad98f4fb81e12cbbb28596feeb5f5c72e59cafe9a362c1', 266, '监管节点-03', 4),
  (6, '0x005330a5400077fb91c8dcf7b0dbf04c4760d0676ccba8ea5b446ef4f9eea7c3', '0x00f6df71aebd23ea28adbe701ce04ceea500f15a8dc49deaeab6d840056dcf4a', 1790577630952, '0x24c9a894337af55e4e4d590ca71dc7fc39d7ffdc70113e1ec5a11d5a499b78ae', 17, '监管节点-01', 4),
  (7, '0x0018bf4627496d9d6a328582e9c4c733ed94bcfa19d837bbbf9c2f11af3f5b16', '0x005330a5400077fb91c8dcf7b0dbf04c4760d0676ccba8ea5b446ef4f9eea7c3', 1790577870952, '0x24c9a894337af55e4e4d590ca71dc7fc39d7ffdc70113e1ec5a11d5a499b78ae', 267, '监管节点-02', 4),
  (8, '0x0028a7f911b7ca00513442b2cc76bc9ccf45507de50f7289188dd58c8938f72a', '0x0018bf4627496d9d6a328582e9c4c733ed94bcfa19d837bbbf9c2f11af3f5b16', 1790578110954, '0x475a5fe91de3293fd228da469454ab79628e160cb80f6be7c011562515bac09e', 422, '监管节点-03', 4),
  (9, '0x00951022dc678b961eff693bd5785946c8a31ed8ced4bad6052c525eaf9d5141', '0x0028a7f911b7ca00513442b2cc76bc9ccf45507de50f7289188dd58c8938f72a', 1790578350957, '0x5ca90b7251a9576f970675761935516eae5eee84964e4dcdadfade06a1e01b94', 93, '监管节点-01', 1);

-- 区块链-区块交易（每 4 笔认证交易打包为一个区块）
-- 【已下线】仅作历史演示存档；实库无 block_transactions 表，重建库时应删除本段 INSERT。
INSERT INTO `block_transactions` (`block_index`, `tx_hash`, `emission_id`, `enterprise_name`, `period`, `amount`, `fingerprint`, `auditor`) VALUES
  (1, '0x40ef1f7cb724092abc8dc7d4b4e7db4b9523e2230047a7993fd2e4f773f09129', 'EM000001', '华东钢铁制造有限公司', '2024-01', 347.43, '0x1bc2c3c442cb267217a59f7291edd8d81fc421ebc5c660e03c1a07c4705da1be', '陈审核'),
  (1, '0x5639f7116ee47bc4ecf368c6dadcce0f2c5eec50aff98de8a3f2db92f73fb0d1', 'EM000002', '华东钢铁制造有限公司', '2024-02', 243.82, '0x6acfd7633aeec1270472db3d77fa8a054f23cc8a81583adc27b8c6fb6b3ea7ca', '刘监管'),
  (1, '0x0db64d7903a6682f6ec8dacdcd36140372769ba8fba6b644b353d12eea751ecd', 'EM000003', '华东钢铁制造有限公司', '2024-03', 1145.08, '0xa61907a3627e8dff4f57795540dca668d7cb13ca77af093fd7474f906da1bee8', '周主任'),
  (1, '0x6d40212a76864c67a24caa57a4ea7f555bc775b764c9da0691081da7212134d5', 'EM000004', '华东钢铁制造有限公司', '2024-04', 491.27, '0x2e7820a5bf493de629e481fa286083e213c158048fe6b526738478058bc2a7e3', '陈审核'),
  (2, '0x20720050bb7d91658d37d5f576c72c82c75d0ed62f38274f1b65752002e9e7eb', 'EM000005', '华东钢铁制造有限公司', '2024-05', 281.8, '0x158510a6a7a7db9a62e6e8500cf1b2d318bc7f9b2c4047962fcd9f5c6a54df60', '刘监管'),
  (2, '0xa669f7d87f4e9973b7bb5ef9837650dac316347942e442b41f7eaa8d3fbfb953', 'EM000006', '华东钢铁制造有限公司', '2024-06', 417.5, '0x9bece588ec4cacd510e3d49f6414edfabfa122ed70a9dc6f5619f336ee501596', '周主任'),
  (2, '0xab92aaffca0ddf963b60d8fd62dc45363894e668f7e94f1a92f333d258e67b69', 'EM000007', '华东钢铁制造有限公司', '2024-07', 1325.21, '0x0a5d3c0fe6505d15163d845a6a09204d32c5595e8aeadbf62b9d2415f8605bcb', '陈审核'),
  (2, '0x04ab47ad61844c00a6ea5322298175f5897e99efa93e313a81418e62489fd473', 'EM000011', '华东钢铁制造有限公司', '2024-11', 1151.67, '0x8e1a0e4255df10995631f651a38d6f3a4feb06c033d4458c7603d644df08c4a7', '刘监管'),
  (3, '0xe4af47f908c587f7b8871db2a883338164613890d32902bcce54155ab72d1ee5', 'EM000012', '华东钢铁制造有限公司', '2024-12', 993.77, '0x162bf45e9882cdcfced59018f212cf1029e8fa572aaebd58cfa3769a14c615ff', '周主任'),
  (3, '0xc0803d3e6745314aec0084cecb4e8b3d225434825f32ccc112e247b5daf18441', 'EM000013', '绿能化工集团', '2024-01', 1448.8, '0xdf5ff9de765eb96a4bc70a3c15545f69487f3c234db86a5b28c8225d68c70f98', '陈审核'),
  (3, '0xde5fe41fe6cc21832c99156e1f651ba6c8163c94bc276a1636ffb794f3ebfe4a', 'EM000014', '绿能化工集团', '2024-02', 1122.31, '0x724ea43f023bd290f7c25590d900e2a5f3168edc6eed858956ede5b5d7653eb9', '刘监管'),
  (3, '0xbf0f0036625eb3a3602aab27fd95675eadb3709723bc6db5fbecdd60ce3c902b', 'EM000015', '绿能化工集团', '2024-03', 448.01, '0x026d9dd09507a423fe207217641f3c83f035562d743b6d2d17676ba8c75da326', '周主任'),
  (4, '0x897aab7fa71c230243f326452a5d1d2521d4b438d1d996582262e9a8db2d497c', 'EM000016', '绿能化工集团', '2024-04', 1324.68, '0x2d0039b826d2448ee3207f38b6e5dec1a14dfa844fb85b8808240032765365fc', '陈审核'),
  (4, '0x651a36618af3cf5a52cf0df6f89ff8c3ca661c807955858ccb986f61d1d1530d', 'EM000017', '绿能化工集团', '2024-05', 474.21, '0xc5ced513e1f3f9ab124121f0724ea9e0dd7e46146ccefc96e7236022e09ef355', '刘监管'),
  (4, '0x50f22d16437bc1bf4e2c76374d9f79f02c7d39ea96838f04e110ccafcae80441', 'EM000018', '绿能化工集团', '2024-06', 498.42, '0xae429844f6b9e34315bf0beb2991ba1100ac92fb8058878ed746002c6f1d42d6', '周主任'),
  (4, '0xace52f743be36ffebcc7119d0772314e940cd1e0a0e4b290b6d80fe1cb11983e', 'EM000019', '绿能化工集团', '2024-07', 1476.11, '0xf7d67a9bf5c64c0c34fc310e34baf258fcf24fbcdf6e19678d86f6a64b2847f4', '陈审核'),
  (5, '0xb4d9ba39d1ea1b37b39613151dd06a1a74bdb0a9b0acd7bb4a5fa8ea5c6ff9c2', 'EM000021', '绿能化工集团', '2024-09', 461.95, '0x194ebba71b6d4c515cfadaa6733fb6faa0aae547850c36e3b1b1d5b1fd3ba4cf', '刘监管'),
  (5, '0xe3b730b9c78529e4870ff0cd3be3b61f1ad11ba4b675b7f159dc1b4eb5226325', 'EM000022', '绿能化工集团', '2024-10', 1354.29, '0xddb21985620a2fe8403ccf0ea710ef57d0a2ac2488c53bde9ff580181bf49bc9', '周主任'),
  (5, '0x73ad99ac86622a93a29c3908fd69c88eebbfb6c8cd6b96ad5747026c3a4e281e', 'EM000026', '南方水泥股份有限公司', '2024-02', 508.6, '0x06fc36eef0e17a0588027a0e9308fc37372e766c2b6ca8a972b702fb783f6ca1', '陈审核'),
  (5, '0xb8e1791ae5ac6de963c0dc93873307841b33a7ee7d84acd88aafc5afd5587c46', 'EM000028', '南方水泥股份有限公司', '2024-04', 1636.01, '0x59b3033435d4f2d457b5c688606cf941eaa503943476482df7571d567e88340c', '刘监管'),
  (6, '0x363ff75a71144d3d05e9bc3cd49d94f0691c98ecbc2aa1e68e7db023a05b592f', 'EM000029', '南方水泥股份有限公司', '2024-05', 555.06, '0xddb398a87f248153c28b638af2416b878f23a3b9c24cf207e9980f06186a6042', '周主任'),
  (6, '0xeb6f559b82a15d4894b15577d17b1717499f05d7a3b6f8cae3d0a6cbd6c20b10', 'EM000030', '南方水泥股份有限公司', '2024-06', 314.77, '0xff0a5302ef0e4e772381d5216dd7f8fb7ee20e77c7b846219d85c024b37307c7', '陈审核'),
  (6, '0x0021b1007637abbcc72b920a64459fdf147f15b9abc1b3c9bf476058a57d9cdb', 'EM000031', '南方水泥股份有限公司', '2024-07', 278.97, '0x3431f449eb6662be6df5b9c1a5f2a7847be2f820111779a8eb0d339529552433', '刘监管'),
  (6, '0x8fb3d5ea7fce25bb65bb416436aa8fe4a3c31d12b7c84f4489cbbcdadb096fb8', 'EM000032', '南方水泥股份有限公司', '2024-08', 1699.26, '0x28832064f8ab0f1c3ea972a8fe7fcfcc929b14b35fa6f3dae68aa2d978029c90', '周主任'),
  (7, '0xa1c32abbae526b27b8502f01e07473ec02b93e4b0261d019f895d0b1b846cfb1', 'EM000033', '南方水泥股份有限公司', '2024-09', 331.03, '0x3d6a0f13680dde3b6eb925eda21bdfb9d226deac1af29ba1e0b75e3939353a8d', '陈审核'),
  (7, '0x1ee6e3c7bf1567bfebc0ccfd7eadabe83d7877ff0c08f7baaeeb46d27e8d7c73', 'EM000034', '南方水泥股份有限公司', '2024-10', 412.13, '0x015eb18252c72262fd6a52720e7fae9e961ebb4ad7804a8b74984d4db5e5775b', '刘监管'),
  (7, '0xdce83b0d168c22547da2febb4e7d639b59807a6962cb10d3c21fc6e0921c2b7b', 'EM000035', '南方水泥股份有限公司', '2024-11', 1633, '0x6f30270d62b48c5ad887889673da89c79c695f6659f7fc439f59cef282e121d4', '周主任'),
  (7, '0x9b9d848c593b781611f9e1f08b8ab0d8f1ff20fc20b2ac2824756a52dc83966e', 'EM000036', '南方水泥股份有限公司', '2024-12', 1349.37, '0x2ba2940b65427a8c9f669c59c509d5c52f4ae31f040ec99b54600987d474e98f', '陈审核'),
  (8, '0x6b393cf52db32523e40d8eb45ea564dbcd1803ce5d3c8a3e7e7a08c7f257e6b4', 'EM000037', '东方电力集团', '2024-01', 1616.87, '0x47c626e0c4c5f0a863e469c97427d531ec321a32229cc8a61b6405a70384d67f', '刘监管'),
  (8, '0xa646f838356ad39b61c0e7814f054e12c4f98e582ae4c19b1516f8f2c973f4a9', 'EM000040', '东方电力集团', '2024-04', 1032.4, '0x9679ef2043279444f193109e92021bd68bf5c39af92cf4dd3b16b8631260d59c', '周主任'),
  (8, '0xa75a53e6513b687c2f06aff6f80b39ed4647b127eb7ba421d35ad4a4074562c3', 'EM000043', '东方电力集团', '2024-07', 1116.87, '0xdaed94638de1a98397d7e95716e5791b2387a3c84c83db004da486b8ac77337f', '陈审核'),
  (8, '0x04b405c7ff452d0dad1965ae63a776150272a8f7ff14a20e9acb05ae34f15312', 'EM000045', '东方电力集团', '2024-09', 1399.29, '0xbdb3aa230271b98a9247c336e1f39dbcf1cfbddf45cce76a293b8430f77aa2e2', '刘监管'),
  (9, '0xc29d3908e94744a9bf9cdccafccf3746441cafda038c67a2391a78be1d66fa92', 'EM000047', '东方电力集团', '2024-11', 558.42, '0x573c30f6442d50144cc02043416f899daf5acd99b44f6b5b09abc83b644acb0c', '周主任');

-- 异常告警（前端静态定义，原样导出）
INSERT INTO `alerts` (`id`, `enterprise_id`, `enterprise_name`, `type`, `type_label`, `level`, `message`, `period`, `status`, `created_at`) VALUES
  ('ALT001', 'ENT20240002', '绿能化工集团', 'data_anomaly', '数据异常', 'high', '2024-08月排放量较上月环比增长 48.6%，超出正常波动阈值（±20%），请核查。', '2024-08', 'pending', '2024-09-02 10:23:11'),
  ('ALT002', 'ENT20240001', '华东钢铁制造有限公司', 'factor_mismatch', '因子不符', 'medium', '排放因子使用与行业指南推荐值偏差 12.3%，建议复核核算口径。', '2024-07', 'pending', '2024-08-28 14:05:33'),
  ('ALT003', 'ENT20240004', '东方电力集团', 'late_report', '迟报', 'medium', '2024-09月排放数据未在截止日期前上报。', '2024-09', 'resolved', '2024-10-06 09:00:00'),
  ('ALT004', 'ENT20240003', '南方水泥股份有限公司', 'data_anomaly', '数据异常', 'low', '活动数据缺失关键字段，已自动标记待补充。', '2024-06', 'resolved', '2024-07-15 16:42:08');
