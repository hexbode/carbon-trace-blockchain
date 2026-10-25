-- =============================================================
-- 03_profile_settings.sql —— 个人中心资料与账号设置
--
-- 【用途】
--   1) users 表新增 email（电子邮箱）、signature（个性签名）两列；
--   2) 新建 user_settings 表，保存站内消息/短信/异地登录/周报四类通知开关。
--
-- 【执行顺序】
--   第 2 位，必须晚于 carbon_emission_db.sql（依赖 users 表已存在）。
--   必须早于 08_user_avatar.sql：后者用 AFTER `signature` 定位 avatar 列。
--
-- 【依赖】
--   数据库 carbon_emission 已存在且 users 表已建（本文件内含 USE，无需 -D 参数）。
--
-- 【幂等性】
--   CREATE TABLE 带 IF NOT EXISTS，可重复执行；
--   ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报
--   ERROR 1060 (Duplicate column name)，故该语句仅能执行一次。
--
-- 注意：库内已有表排序规则为 utf8mb4_0900_ai_ci，此处显式指定，
--       避免后续 JOIN 出现 "Illegal mix of collations"。
-- =============================================================
USE `carbon_emission`;

-- 1. users 表新增电子邮箱、个性签名
--    两列均可空，避免历史用户因缺省值迁移失败；
--    AFTER 指定列顺序，与前端个人资料表单的字段顺序保持一致。
ALTER TABLE `users`
  ADD COLUMN `email`     VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NULL COMMENT '电子邮箱'     AFTER `phone`,
  ADD COLUMN `signature` VARCHAR(255) COLLATE utf8mb4_0900_ai_ci NULL COMMENT '个性签名' AFTER `email`;

-- 2. 用户账号设置表 user_settings
--    与 users 一对一：user_id 既是主键也是外键，一个用户最多一行设置；
--    删除用户前需先删除本表记录，否则外键约束会阻止删除。
--    四个开关均为 TINYINT(1) NOT NULL DEFAULT 1：默认全部开启，写入 0 表示关闭。
--    字段分组：① 主键与外键 ② 通知开关 ③ 更新时间
CREATE TABLE IF NOT EXISTS `user_settings` (
  -- ① 主键与外键：用户 ID，同时约束一用户一行
  `user_id`       INT NOT NULL COMMENT '用户ID',
  -- ② 通知开关
  `msg_notify`    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '站内消息通知',
  `sms_notify`    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '短信通知',
  `login_remind`  TINYINT(1) NOT NULL DEFAULT 1 COMMENT '异地登录提醒',
  `weekly_report` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '周报推送',
  -- ③ 更新时间：由业务写入，无自动更新语义
  `updated_at`    DATETIME DEFAULT NULL COMMENT '更新时间',
  -- 主键：user_id（同时作为外键，保证一对一）
  PRIMARY KEY (`user_id`),
  -- 用户外键：设置不能脱离用户独立存在
  CONSTRAINT `fk_settings_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号设置表';
