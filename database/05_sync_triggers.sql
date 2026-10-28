-- ============================================================
-- 05_sync_triggers.sql —— 排放数据联动触发器
--
-- 【用途】
--   以 emission_records 为主表，维护「排放 → 报告 → 认证」三张同号表的数据一致：
--     新增排放记录 → 自动生成同号的报告记录（待审核）；
--     修改排放记录 → 把公共字段同步到报告/认证；
--     删除排放记录 → 先删除其余两张表里同号的记录，再让主表完成删除。
--
-- 【执行顺序】
--   第 4 位，必须晚于 carbon_emission_db.sql（三张表与外键需已存在）。
--   应在数据初始化完成后执行，避免历史数据绕过触发器。
--
-- 【依赖】
--   emission_records / report_records / certification_records 三表必须存在。
--   执行账号需要 TRIGGER 权限；若开启 binlog 且未设置
--   log_bin_trust_function_creators=1，还需 SUPER 权限。
--
-- 【幂等性】
--   可重复执行：先 DROP TRIGGER IF EXISTS 再 CREATE，触发器定义总以本文件为准。
--
-- 【链上数据边界】
--   区块与交易由链网关（stub / WeBASE）实时提供，业务库不保存链表；
--   早期本地模拟链表 blocks / block_transactions 已下线，本文件不再同步这两张表。
--
-- 【为什么只做单向】
--   MySQL 禁止「触发器里再回写触发它的那条语句正在改的表」，双向联动会报
--   ERROR 1442 (Can't update table xxx ... already used by statement which invoked this trigger)。
--   因此反向（直接改/删 report_records 等衍生表）不会自动回溯到排放记录，
--   这部分由后端接口保证（删除排放记录时后端会一并清理报告与认证记录）。
--
-- 【同步字段的写法】
--   带 «值不同才写» 的条件（<=> 空值安全比较），避免相互触发形成死循环。
--   状态字段（status）不在同步范围：报告审核状态由后端接口负责回写排放记录。
--
-- 用法：mysql -uroot -p -D carbon_emission < 05_sync_triggers.sql
-- 回滚：执行文件顶部的 DROP TRIGGER 语句即可移除全部联动
-- ============================================================

-- 清理历史触发器，保证重复执行时定义唯一。
-- 其中 trg_emission_ad、trg_report_*、trg_cert_*、trg_tx_* 属于旧版双向联动方案，
-- 当前版本只重建 trg_emission_ai / trg_emission_au / trg_emission_bd 三个，
-- 其余在此仅做删除（幂等清理），不再创建。
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

-- 触发器体内含分号，临时改用 $$ 作为语句结束符；DELIMITER ; 在本文件后部恢复
DELIMITER $$

-- ① 新增排放记录 → 生成报告记录（已存在则跳过，兼容后端接口自身的插入）
--    报告编号直接复用排放记录编号，保证四表「同号」可互查；
--    初始状态固定为 pending，提交人/时间取自排放记录，审核字段留空待审核回填；
--    ON DUPLICATE KEY UPDATE id = id 是空操作：主键冲突时保持已有报告不变，
--    避免后端已先行插入报告时被触发器覆盖。
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

-- ② 修改排放记录 → 同步到报告 / 认证
--    同步范围：企业编号、企业名称、报告期、排放量、电子防伪码；
--              不含 status（由审核接口单独回写），也不含来源/活动数据等仅主表使用的字段。
--    每条 UPDATE 都带「任一字段值不同才执行」的条件，正常更新时子表命中 0 行即结束，
--    避免触发器之间相互触发形成递归。
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
END$$

-- ③ 删除排放记录 → 先删除报告 / 认证中同号的记录
--    必须用 BEFORE DELETE：报告与认证表对 emission_records 有外键约束，
--    外键检查发生在语句执行时，AFTER DELETE 触发器来不及清理子表（会报 ERROR 1451）
--    删除顺序：报告 → 认证，先清子表再让主表删除继续执行。
CREATE TRIGGER trg_emission_bd BEFORE DELETE ON emission_records
FOR EACH ROW
BEGIN
  DELETE FROM report_records WHERE id = OLD.id;
  DELETE FROM certification_records WHERE emission_id = OLD.id;
END$$

-- 恢复默认分号结束符，后续普通 SQL 才能正常按 ; 切分
DELIMITER ;
