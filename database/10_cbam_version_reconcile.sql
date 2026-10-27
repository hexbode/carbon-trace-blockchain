-- =============================================================
-- 10_cbam_version_reconcile.sql —— CBAM 数据版本号与定时对账（建议 2 + 3）
--
-- 【用途】
--   1) cbam_data_versions：按“企业 + 年度”记录排放数据版本，任何排放记录
--      新增/修改/删除都会使版本 +1；申报包生成时把当时的版本写入
--      cbam_declarations.data_version，用于快速发现“数据已变化”；
--   2) cbam_reconciliation_logs：定时/手动对账结果留痕，记录快照版本、
--      实时版本、快照合计、实时合计与一致性结论。
--
-- 【幂等性】
--   可重复执行；ALTER 列通过 information_schema 判断后动态执行。
-- =============================================================
USE `carbon_emission`;

-- 1. 排放数据版本表（企业 + 年度唯一）
CREATE TABLE IF NOT EXISTS `cbam_data_versions` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `enterprise_id` VARCHAR(20) NOT NULL COMMENT '企业编号',
  `report_year`   INT         NOT NULL COMMENT '年度',
  `version`       BIGINT      NOT NULL DEFAULT 0 COMMENT '数据版本，排放记录变更时 +1',
  `updated_at`    DATETIME    DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_dv` (`enterprise_id`, `report_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 排放数据版本';

-- 2. 申报包增加快照版本列（已存在则跳过）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cbam_declarations' AND COLUMN_NAME = 'data_version'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `cbam_declarations` ADD COLUMN `data_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''生成时的数据版本快照'' AFTER `package_json`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 对账日志表
CREATE TABLE IF NOT EXISTS `cbam_reconciliation_logs` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `declaration_id`   BIGINT        NOT NULL COMMENT '申报包ID',
  `declaration_no`   VARCHAR(30)   DEFAULT NULL COMMENT '申报包编号',
  `enterprise_id`    VARCHAR(20)   DEFAULT NULL COMMENT '企业编号',
  `report_year`      INT           DEFAULT NULL COMMENT '年度',
  `snapshot_version` BIGINT        DEFAULT NULL COMMENT '申报包快照版本',
  `live_version`     BIGINT        DEFAULT NULL COMMENT '当前数据版本',
  `snapshot_total`   DECIMAL(18,3) DEFAULT NULL COMMENT '快照隐含排放合计 tCO2e',
  `live_total`       DECIMAL(18,3) DEFAULT NULL COMMENT '实时隐含排放合计 tCO2e',
  `status`           VARCHAR(20)   NOT NULL COMMENT '结论：consistent / stale',
  `detail`           VARCHAR(500)  DEFAULT NULL COMMENT '差异说明',
  `checked_at`       DATETIME      DEFAULT NULL COMMENT '对账时间',
  PRIMARY KEY (`id`),
  KEY `idx_cbam_rl_decl` (`declaration_id`, `checked_at`),
  KEY `idx_cbam_rl_status` (`status`, `checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 申报包对账日志';

-- 4. 为已有数据初始化版本：有排放记录的企业/年度从 1 开始
INSERT INTO `cbam_data_versions` (`enterprise_id`, `report_year`, `version`, `updated_at`)
SELECT `enterprise_id`, `report_year`, 1, NOW()
FROM `cbam_product_emissions`
GROUP BY `enterprise_id`, `report_year`
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

-- 5. 让已有申报包的快照版本对齐当前版本（仅补齐 0 值，不覆盖已设置值）
UPDATE `cbam_declarations` d
JOIN `cbam_data_versions` v
  ON v.`enterprise_id` = d.`enterprise_id` AND v.`report_year` = d.`report_year`
SET d.`data_version` = v.`version`
WHERE d.`data_version` = 0;
