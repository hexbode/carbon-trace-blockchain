-- ============================================================
-- 08_user_avatar.sql —— 用户头像字段
--
-- 【用途】
--   个人中心「更换头像」需要把上传后的图片地址存到用户记录上。
--   图片文件本身保存在后端 uploads/avatar 目录，数据库只存相对地址
--   （如 /uploads/avatar/xxx.jpg），避免把二进制文件塞入库。
--
-- 【执行顺序】
--   第 7 位（最后），必须晚于 03_profile_settings.sql：
--   AFTER `signature` 依赖 03 已为 users 追加 signature 列。
--
-- 【依赖】
--   库 carbon_emission 已存在且 users 表含 signature 列（用 -D 指定库）。
--
-- 【幂等性】
--   ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报
--   ERROR 1060 (Duplicate column name)，故只能执行一次。
-- ============================================================

ALTER TABLE `users`
  ADD COLUMN `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像地址' AFTER `signature`;
