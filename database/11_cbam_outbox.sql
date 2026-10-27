-- =============================================================
-- 11_cbam_outbox.sql —— CBAM 事务性 Outbox + 监管同步收件箱
--
-- 【用途】
--   1) cbam_outbox：与申报包状态变更同一事务写入事件，后台中继（Relay）
--      再投递到 RabbitMQ；避免“业务已提交但消息未发出”的双写不一致；
--   2) cbam_regulator_inbox：监管侧消费幂等收件箱，event_id 唯一，
--      重复投递只入库一次；
--   3) 为对账日志的 90 天清理补 checked_at 索引。
--
-- 【幂等性】可重复执行。
-- =============================================================
USE `carbon_emission`;

-- 1. 事务性 Outbox
CREATE TABLE IF NOT EXISTS `cbam_outbox` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`       VARCHAR(64)  NOT NULL COMMENT '事件ID（UUID），消费端幂等键',
  `aggregate_type` VARCHAR(40)  NOT NULL DEFAULT 'CBAM_DECLARATION' COMMENT '聚合类型',
  `aggregate_id`   BIGINT       NOT NULL COMMENT '聚合ID（申报包ID）',
  `event_type`     VARCHAR(40)  NOT NULL COMMENT '事件类型：DECLARATION_GENERATED/SUBMITTED/APPROVED/RETURNED',
  `payload`        LONGTEXT     DEFAULT NULL COMMENT '事件 JSON',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/SENT/FAILED',
  `retry_count`    INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_at`  DATETIME     DEFAULT NULL COMMENT '下次重试时间',
  `last_error`     VARCHAR(500) DEFAULT NULL COMMENT '最近一次错误',
  `created_at`     DATETIME     DEFAULT NULL COMMENT '创建时间',
  `sent_at`        DATETIME     DEFAULT NULL COMMENT '投递成功时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_outbox_event` (`event_id`),
  KEY `idx_cbam_outbox_status` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 事务性 Outbox';

-- 2. 监管同步收件箱（幂等）
CREATE TABLE IF NOT EXISTS `cbam_regulator_inbox` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`       VARCHAR(64)  NOT NULL COMMENT '事件ID，唯一',
  `declaration_id` BIGINT       DEFAULT NULL COMMENT '申报包ID',
  `declaration_no` VARCHAR(30)  DEFAULT NULL COMMENT '申报包编号',
  `event_type`     VARCHAR(40)  DEFAULT NULL COMMENT '事件类型',
  `payload`        LONGTEXT     DEFAULT NULL COMMENT '事件 JSON',
  `received_at`    DATETIME     DEFAULT NULL COMMENT '消费时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_inbox_event` (`event_id`),
  KEY `idx_cbam_inbox_decl` (`declaration_id`, `received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 监管同步收件箱';

-- 3. 对账日志归档表（90 天前的日志先归档再删除）
CREATE TABLE IF NOT EXISTS `cbam_reconciliation_logs_archive` (
  `archive_id`       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '归档主键',
  `log_id`           BIGINT        NOT NULL COMMENT '原日志ID',
  `declaration_id`   BIGINT        DEFAULT NULL COMMENT '申报包ID',
  `declaration_no`   VARCHAR(30)   DEFAULT NULL COMMENT '申报包编号',
  `enterprise_id`    VARCHAR(20)   DEFAULT NULL COMMENT '企业编号',
  `report_year`      INT           DEFAULT NULL COMMENT '年度',
  `snapshot_version` BIGINT        DEFAULT NULL COMMENT '快照版本',
  `live_version`     BIGINT        DEFAULT NULL COMMENT '实时版本',
  `snapshot_total`   DECIMAL(18,3) DEFAULT NULL COMMENT '快照合计',
  `live_total`       DECIMAL(18,3) DEFAULT NULL COMMENT '实时合计',
  `status`           VARCHAR(20)   DEFAULT NULL COMMENT '结论',
  `detail`           VARCHAR(500)  DEFAULT NULL COMMENT '说明',
  `checked_at`       DATETIME      DEFAULT NULL COMMENT '原对账时间',
  `archived_at`      DATETIME      DEFAULT NULL COMMENT '归档时间',
  PRIMARY KEY (`archive_id`),
  UNIQUE KEY `uk_cbam_rl_archive_log` (`log_id`),
  KEY `idx_cbam_rl_archive_checked` (`checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 对账日志归档';

-- 4. 对账日志 checked_at 索引（90 天归档清理按时间范围删除）
SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cbam_reconciliation_logs' AND INDEX_NAME = 'idx_cbam_rl_checked'
);
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `cbam_reconciliation_logs` ADD INDEX `idx_cbam_rl_checked` (`checked_at`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
