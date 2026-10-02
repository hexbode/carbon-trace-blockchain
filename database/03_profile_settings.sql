-- =============================================================
-- 个人中心资料与账号设置迁移脚本
-- 1) users 表新增 email / signature 两列
-- 2) 新建 user_settings 表（按 users.id 关联）
-- 注意：库内已有表排序规则为 utf8mb4_0900_ai_ci，此处显式指定，
--       避免后续 JOIN 出现 "Illegal mix of collations"。
-- =============================================================
USE `carbon_emission`;

-- 1. users 表新增电子邮箱、个性签名
ALTER TABLE `users`
  ADD COLUMN `email`     VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NULL COMMENT '电子邮箱'     AFTER `phone`,
  ADD COLUMN `signature` VARCHAR(255) COLLATE utf8mb4_0900_ai_ci NULL COMMENT '个性签名' AFTER `email`;

-- 2. 用户账号设置表
CREATE TABLE IF NOT EXISTS `user_settings` (
  `user_id`       INT NOT NULL COMMENT '用户ID',
  `msg_notify`    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '站内消息通知',
  `sms_notify`    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '短信通知',
  `login_remind`  TINYINT(1) NOT NULL DEFAULT 1 COMMENT '异地登录提醒',
  `weekly_report` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '周报推送',
  `updated_at`    DATETIME DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_settings_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号设置表';
