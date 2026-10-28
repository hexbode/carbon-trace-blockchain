-- ============================================================
-- 13_cbam_anchor.sql —— CBAM 申报包链上锚点字段（P1）
--
-- 【用途】
--   为 cbam_declarations 增加链上锚点相关列：
--     · snapshot_hash  申报包 JSON 的 Keccak-256（生成快照时写入）；
--     · anchor_tx_hash 锚点写链交易哈希；
--     · anchor_block   锚点交易所在区块高度；
--     · anchored_at    锚点写链成功时间。
--   快照原文仍保存在 package_json，链上只存哈希与数据版本，用于不可篡改的时间/版本证明。
--
-- 【执行顺序】
--   必须晚于 09_cbam.sql（cbam_declarations 表已存在）。
--
-- 【幂等性】
--   可重复执行：逐列检查 information_schema，已存在则跳过，不会报 1060。
--
-- 用法：mysql -uroot -p -D carbon_emission < 13_cbam_anchor.sql
-- ============================================================

DROP PROCEDURE IF EXISTS tmp_add_cbam_anchor_columns;

DELIMITER $$
CREATE PROCEDURE tmp_add_cbam_anchor_columns()
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name = 'cbam_declarations'
         AND column_name = 'snapshot_hash') = 0 THEN
    ALTER TABLE `cbam_declarations`
      ADD COLUMN `snapshot_hash` VARCHAR(64) DEFAULT NULL
      COMMENT '申报包 JSON 的 Keccak-256（64 位十六进制）' AFTER `package_json`;
  END IF;

  IF (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name = 'cbam_declarations'
         AND column_name = 'anchor_tx_hash') = 0 THEN
    ALTER TABLE `cbam_declarations`
      ADD COLUMN `anchor_tx_hash` VARCHAR(80) DEFAULT NULL
      COMMENT '锚点写链交易哈希' AFTER `snapshot_hash`;
  END IF;

  IF (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name = 'cbam_declarations'
         AND column_name = 'anchor_block') = 0 THEN
    ALTER TABLE `cbam_declarations`
      ADD COLUMN `anchor_block` BIGINT DEFAULT NULL
      COMMENT '锚点交易所在区块高度' AFTER `anchor_tx_hash`;
  END IF;

  IF (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name = 'cbam_declarations'
         AND column_name = 'anchored_at') = 0 THEN
    ALTER TABLE `cbam_declarations`
      ADD COLUMN `anchored_at` DATETIME DEFAULT NULL
      COMMENT '锚点写链完成时间' AFTER `anchor_block`;
  END IF;
END$$
DELIMITER ;

CALL tmp_add_cbam_anchor_columns();
DROP PROCEDURE tmp_add_cbam_anchor_columns;
