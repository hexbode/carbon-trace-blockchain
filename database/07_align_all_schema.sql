-- ============================================================
-- 结构对齐（完整版）：把实库中「本应允许为空、却被建成 NOT NULL」的列改回可空
--
-- 背景：database/carbon_emission_db.sql 是表结构的基准定义，其中大量可选字段是
--       DEFAULT NULL；但实际数据库里这些列被统一建成了 NOT NULL 且无默认值，
--       导致后端插入时报「Field 'xxx' doesn't have a default value」：
--         · 新增企业        （缺 address / scope / registered_at）
--         · 新增审核机构    （缺 created_at）
--         · 新增用户        （缺 created_at，已在 06 修复）
--         · 新增排放记录    （缺 created_at / type_label 等）
--       此外 users.name 被建成了 VARCHAR(8)，比基准文件（VARCHAR(50)）窄，
--       前端姓名输入框上限是 20 字，超过 8 字会报 Data too long，此处一并对齐。
--
-- 说明：仅放宽约束 / 放宽长度，不修改数据，不影响索引、外键与字符集。
-- 关系：06_align_schema.sql 处理了 report_records 与 certification_records，
--       本文件处理其余各表，两个文件都要执行。
--
-- 用法：mysql -uroot -p -D carbon_emission < 07_align_all_schema.sql
-- ============================================================

-- 1. 企业信息表
ALTER TABLE `enterprises`
  MODIFY COLUMN `contact`       VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
  MODIFY COLUMN `phone`         VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `address`       VARCHAR(200) DEFAULT NULL COMMENT '注册地址',
  MODIFY COLUMN `scope`         VARCHAR(200) DEFAULT NULL COMMENT '核算范围',
  MODIFY COLUMN `registered_at` DATE         DEFAULT NULL COMMENT '注册时间';

-- 2. 碳排放因子库表
ALTER TABLE `emission_factors`
  MODIFY COLUMN `standard`   VARCHAR(50) DEFAULT NULL COMMENT '依据标准',
  MODIFY COLUMN `updated_at` DATE        DEFAULT NULL COMMENT '更新时间';

-- 3. 审核机构表
ALTER TABLE `audit_organizations`
  MODIFY COLUMN `contact`       VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
  MODIFY COLUMN `phone`         VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `qualification` VARCHAR(50)  DEFAULT NULL COMMENT '资质证书号',
  MODIFY COLUMN `scope`         VARCHAR(200) DEFAULT NULL COMMENT '业务范围',
  MODIFY COLUMN `created_at`    DATE         DEFAULT NULL COMMENT '入驻时间';

-- 4. 系统用户表（name 同时按基准文件放宽到 VARCHAR(50)）
ALTER TABLE `users`
  MODIFY COLUMN `name`       VARCHAR(50) DEFAULT NULL COMMENT '姓名',
  MODIFY COLUMN `phone`      VARCHAR(30) DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `created_at` DATE        DEFAULT NULL COMMENT '创建时间';

-- 5. 碳排放数据记录表
ALTER TABLE `emission_records`
  MODIFY COLUMN `activity_unit` VARCHAR(20) DEFAULT NULL COMMENT '活动数据单位',
  MODIFY COLUMN `fingerprint`   VARCHAR(70) DEFAULT NULL COMMENT '电子防伪码',
  MODIFY COLUMN `created_at`    DATETIME    DEFAULT NULL COMMENT '提交时间',
  MODIFY COLUMN `reporter`      VARCHAR(50) DEFAULT NULL COMMENT '填报人';

-- 6. 区块表
ALTER TABLE `blocks`
  MODIFY COLUMN `merkle_root` VARCHAR(70) DEFAULT NULL COMMENT '默克尔根',
  MODIFY COLUMN `miner`       VARCHAR(50) DEFAULT NULL COMMENT '打包节点';

-- 7. 区块交易表
ALTER TABLE `block_transactions`
  MODIFY COLUMN `emission_id`     VARCHAR(20)   DEFAULT NULL COMMENT '关联排放记录编号',
  MODIFY COLUMN `enterprise_name` VARCHAR(100)  DEFAULT NULL COMMENT '企业名称',
  MODIFY COLUMN `period`          VARCHAR(10)   DEFAULT NULL COMMENT '报告期',
  MODIFY COLUMN `amount`          DECIMAL(14,2) DEFAULT NULL COMMENT '排放量',
  MODIFY COLUMN `fingerprint`     VARCHAR(70)   DEFAULT NULL COMMENT '电子防伪码',
  MODIFY COLUMN `auditor`         VARCHAR(50)   DEFAULT NULL COMMENT '审核人';

-- 8. 异常告警表
ALTER TABLE `alerts`
  MODIFY COLUMN `enterprise_name` VARCHAR(100) DEFAULT NULL COMMENT '企业名称',
  MODIFY COLUMN `type_label`      VARCHAR(30)  DEFAULT NULL COMMENT '类型中文名',
  MODIFY COLUMN `message`         VARCHAR(300) DEFAULT NULL COMMENT '告警内容',
  MODIFY COLUMN `period`          VARCHAR(10)  DEFAULT NULL COMMENT '涉及报告期',
  MODIFY COLUMN `created_at`      DATETIME     DEFAULT NULL COMMENT '触发时间';

-- 9. 监管机构表
ALTER TABLE `regulator_organizations`
  MODIFY COLUMN `region`     VARCHAR(50)  DEFAULT NULL COMMENT '所属区域',
  MODIFY COLUMN `contact`    VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
  MODIFY COLUMN `phone`      VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `address`    VARCHAR(200) DEFAULT NULL COMMENT '办公地址',
  MODIFY COLUMN `scope`      VARCHAR(200) DEFAULT NULL COMMENT '监管职责',
  MODIFY COLUMN `created_at` DATE         DEFAULT NULL COMMENT '成立时间';
