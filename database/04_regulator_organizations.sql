-- ============================================================
-- 监管机构表（监管端）
-- 说明：与 enterprises（企业端）、audit_organizations（审核端）并列，
--       三者在「企业管理」页通过顶部「类型」切换分别维护。
-- 用法：mysql -uroot -p -D carbon_emission < 04_regulator_organizations.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `regulator_organizations` (
  `id`          VARCHAR(20)  NOT NULL              COMMENT '机构编号',
  `name`        VARCHAR(100) NOT NULL              COMMENT '机构名称',
  `region`      VARCHAR(50)           DEFAULT NULL COMMENT '所属区域',
  `contact`     VARCHAR(50)           DEFAULT NULL COMMENT '联系人',
  `phone`       VARCHAR(30)           DEFAULT NULL COMMENT '联系电话',
  `address`     VARCHAR(200)          DEFAULT NULL COMMENT '办公地址',
  `scope`       VARCHAR(200)          DEFAULT NULL COMMENT '监管职责',
  `status`      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active 正常 / inactive 停用',
  `created_at`  DATE                  DEFAULT NULL COMMENT '成立时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='监管机构表';
