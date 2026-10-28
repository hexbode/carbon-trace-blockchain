-- ============================================================
-- 12_must_change_password.sql —— 首次登录强制改密
--
-- 【用途】
--   为 users 表增加 must_change_password 标志，并把仓库内置的演示账号置为 1。
--   演示账号统一使用弱密码 123456，登录后必须先完成改密，才能访问其余业务接口；
--   监管端重置密码时也会把该标志置 1，强制用户下次登录后自行修改。
--
-- 【执行顺序】
--   最后执行，必须晚于 carbon_emission_db.sql、07_align_all_schema.sql：
--   users 表及其 status 列需已存在（新列以 AFTER `status` 追加）。
--
-- 【幂等性】
--   可重复执行：
--     · 列已存在时跳过 ADD COLUMN，不会报 1060；
--     · 演示账号置 1 只在「首次加列」的分支内执行，
--       避免把后续已自行改密的账号再次打回强制改密状态。
--
-- 【依赖】
--   库 carbon_emission 已存在（用 -D carbon_emission 指定库）。
--
-- 用法：mysql -uroot -p -D carbon_emission < 12_must_change_password.sql
-- ============================================================

DROP PROCEDURE IF EXISTS tmp_add_must_change_password;

DELIMITER $$
CREATE PROCEDURE tmp_add_must_change_password()
BEGIN
  -- 仅当列不存在时加列；存在即跳过，保证脚本可重复执行
  IF (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name = 'users'
         AND column_name = 'must_change_password') = 0 THEN

    ALTER TABLE `users`
      ADD COLUMN `must_change_password` TINYINT(1) NOT NULL DEFAULT 0
      COMMENT '首次登录强制改密：1 需改密 / 0 正常' AFTER `status`;

    -- 仓库内置演示账号统一弱密码，首次登录必须改密
    UPDATE `users`
       SET `must_change_password` = 1
     WHERE `username` IN ('enterprise01', 'enterprise02', 'enterprise03', 'enterprise04',
                          'auditor01', 'auditor02', 'regulator01', 'admin');
  END IF;
END$$
DELIMITER ;

CALL tmp_add_must_change_password();
DROP PROCEDURE tmp_add_must_change_password;
