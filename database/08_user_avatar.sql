-- ============================================================
-- 用户头像字段
-- 说明：个人中心「更换头像」需要把上传后的图片地址存到用户记录上。
--       图片文件本身保存在后端 uploads/avatar 目录，数据库只存相对地址（如 /uploads/avatar/xxx.jpg）。
-- 用法：mysql -uroot -p -D carbon_emission < 08_user_avatar.sql
-- ============================================================

ALTER TABLE `users`
  ADD COLUMN `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像地址' AFTER `signature`;
