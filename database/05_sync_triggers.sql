-- ============================================================
-- 四张同号表（emission_records / report_records / certification_records / block_transactions）
-- 的数据联动触发器
--
-- 规则（以 emission_records 为主表）：
--   新增排放记录 → 自动生成同号的报告记录
--   修改排放记录 → 把公共字段（企业编号/企业名称/报告期/排放量/电子防伪码）同步到其余三张表
--   删除排放记录 → 删除其余三张表里同号的记录
--
-- 为什么只做单向：
--   MySQL 禁止「触发器里再回写触发它的那条语句正在改的表」，双向联动会报
--   ERROR 1442 (Can't update table xxx ... already used by statement which invoked this trigger)。
--   因此反向（直接改/删 report_records 等衍生表）不会自动回溯到排放记录，
--   这部分由后端接口保证（删除排放记录时后端会一并清理报告与认证记录）。
--
-- 同步字段的写法带 «值不同才写» 的条件，避免相互触发形成死循环。
-- 状态字段（status）不在同步范围：报告审核状态由后端接口负责回写排放记录。
--
-- 用法：mysql -uroot -p -D carbon_emission < 05_sync_triggers.sql
-- 回滚：执行文件顶部的 DROP TRIGGER 语句即可移除全部联动
-- ============================================================

DROP TRIGGER IF EXISTS trg_emission_ai;
DROP TRIGGER IF EXISTS trg_emission_au;
DROP TRIGGER IF EXISTS trg_emission_ad;
DROP TRIGGER IF EXISTS trg_emission_bd;
DROP TRIGGER IF EXISTS trg_report_au;
DROP TRIGGER IF EXISTS trg_report_ad;
DROP TRIGGER IF EXISTS trg_cert_au;
DROP TRIGGER IF EXISTS trg_cert_ad;
DROP TRIGGER IF EXISTS trg_tx_au;
DROP TRIGGER IF EXISTS trg_tx_ad;

DELIMITER $$

-- ① 新增排放记录 → 生成报告记录（已存在则跳过，兼容后端接口自身的插入）
CREATE TRIGGER trg_emission_ai AFTER INSERT ON emission_records
FOR EACH ROW
BEGIN
  INSERT INTO report_records
    (id, emission_id, enterprise_id, enterprise_name, period, source_name,
     amount, status, fingerprint, submitter, submitted_at)
  VALUES
    (NEW.id, NEW.id, NEW.enterprise_id, NEW.enterprise_name, NEW.period, NEW.source_name,
     NEW.amount, 'pending', NEW.fingerprint, NEW.reporter, IFNULL(NEW.created_at, NOW()))
  ON DUPLICATE KEY UPDATE id = id;
END$$

-- ② 修改排放记录 → 同步到报告 / 认证 / 链上交易
CREATE TRIGGER trg_emission_au AFTER UPDATE ON emission_records
FOR EACH ROW
BEGIN
  UPDATE report_records SET
    enterprise_id = NEW.enterprise_id,
    enterprise_name = NEW.enterprise_name,
    period = NEW.period,
    amount = NEW.amount,
    fingerprint = NEW.fingerprint
  WHERE id = NEW.id
    AND (NOT (enterprise_id <=> NEW.enterprise_id)
      OR NOT (enterprise_name <=> NEW.enterprise_name)
      OR NOT (period <=> NEW.period)
      OR NOT (amount <=> NEW.amount)
      OR NOT (fingerprint <=> NEW.fingerprint));

  UPDATE certification_records SET
    enterprise_id = NEW.enterprise_id,
    enterprise_name = NEW.enterprise_name,
    period = NEW.period,
    amount = NEW.amount,
    fingerprint = NEW.fingerprint
  WHERE emission_id = NEW.id
    AND (NOT (enterprise_id <=> NEW.enterprise_id)
      OR NOT (enterprise_name <=> NEW.enterprise_name)
      OR NOT (period <=> NEW.period)
      OR NOT (amount <=> NEW.amount)
      OR NOT (fingerprint <=> NEW.fingerprint));

  UPDATE block_transactions SET
    enterprise_name = NEW.enterprise_name,
    period = NEW.period,
    amount = NEW.amount,
    fingerprint = NEW.fingerprint
  WHERE emission_id = NEW.id
    AND (NOT (enterprise_name <=> NEW.enterprise_name)
      OR NOT (period <=> NEW.period)
      OR NOT (amount <=> NEW.amount)
      OR NOT (fingerprint <=> NEW.fingerprint));
END$$

-- ③ 删除排放记录 → 先删除报告 / 认证 / 链上交易中同号的记录
--    必须用 BEFORE DELETE：报告与认证表对 emission_records 有外键约束，
--    外键检查发生在语句执行时，AFTER DELETE 触发器来不及清理子表（会报 ERROR 1451）
CREATE TRIGGER trg_emission_bd BEFORE DELETE ON emission_records
FOR EACH ROW
BEGIN
  DELETE FROM report_records WHERE id = OLD.id;
  DELETE FROM certification_records WHERE emission_id = OLD.id;
  DELETE FROM block_transactions WHERE emission_id = OLD.id;

  -- 按实际交易数重算各区块的交易计数：
  -- 用「重算」而不是「减 1」，这样即使链上交易是直接用 SQL 插入的（没走应用层计数 +1），
  -- 删除后也能自动纠正，不会出现盲减导致的偏差
  UPDATE blocks b
  SET tx_count = (SELECT COUNT(*) FROM block_transactions t WHERE t.block_index = b.block_index);
END$$

DELIMITER ;

-- ============================================================
-- 一次性数据修复：按实际交易数重算各区块的交易计数
-- （幂等，可重复执行；用于修正历史删除造成的 tx_count 虚高）
-- ============================================================
UPDATE blocks b
SET tx_count = (SELECT COUNT(*) FROM block_transactions t WHERE t.block_index = b.block_index);
