-- ============================================================
-- 结构对齐：把实库中「本应允许为空、却被建成 NOT NULL」的列改回可空
--
-- 背景：database/carbon_emission_db.sql 是表结构的基准定义，其中
--       report_records / certification_records 的多列都是 DEFAULT NULL；
--       但实际数据库里这些列被建成了 NOT NULL 且无默认值，导致后端插入时报
--       「Field 'xxx' doesn't have a default value」：
--         · 新增排放记录（插入 report_records 时缺 auditor/audited_at/audit_opinion）
--         · 审核发证（审核意见为空时插入 certification_records 缺 audit_opinion）
-- 说明：仅放宽约束，不修改数据、不影响已有索引与字符集。
--
-- 用法：mysql -uroot -p -D carbon_emission < 06_align_schema.sql
-- ============================================================

-- 报告提交记录表：这些列允许为空
ALTER TABLE `report_records`
  MODIFY COLUMN `emission_id`   VARCHAR(20)  DEFAULT NULL COMMENT '关联排放记录编号',
  MODIFY COLUMN `source_name`   VARCHAR(50)  DEFAULT NULL COMMENT '排放源名称',
  MODIFY COLUMN `fingerprint`   VARCHAR(70)  DEFAULT NULL COMMENT '电子防伪码',
  MODIFY COLUMN `submitter`     VARCHAR(50)  DEFAULT NULL COMMENT '提交人',
  MODIFY COLUMN `submitted_at`  DATETIME     DEFAULT NULL COMMENT '提交时间',
  MODIFY COLUMN `auditor`       VARCHAR(50)  DEFAULT NULL COMMENT '审核人',
  MODIFY COLUMN `audited_at`    DATETIME     DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `audit_opinion` TEXT                  COMMENT '审核意见';

-- 认证记录表：这些列允许为空
ALTER TABLE `certification_records`
  MODIFY COLUMN `emission_id`   VARCHAR(20)  DEFAULT NULL COMMENT '关联排放记录编号',
  MODIFY COLUMN `fingerprint`   VARCHAR(70)  DEFAULT NULL COMMENT '电子防伪码',
  MODIFY COLUMN `tx_hash`       VARCHAR(70)  DEFAULT NULL COMMENT '区块链交易哈希',
  MODIFY COLUMN `block_index`   INT          DEFAULT NULL COMMENT '所在区块高度',
  MODIFY COLUMN `auditor`       VARCHAR(50)  DEFAULT NULL COMMENT '审核人',
  MODIFY COLUMN `audit_opinion` TEXT                  COMMENT '审核意见',
  MODIFY COLUMN `certified_at`  DATETIME     DEFAULT NULL COMMENT '认证时间';
