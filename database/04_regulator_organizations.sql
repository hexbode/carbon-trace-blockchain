-- ============================================================
-- 04_regulator_organizations.sql —— 监管机构表（监管端）
--
-- 【用途】
--   维护监管机构档案，与 enterprises（企业端）、audit_organizations（审核端）并列；
--   三者在「企业管理」页通过顶部「类型」切换分别维护。
--
-- 【执行顺序】
--   第 3 位，晚于 carbon_emission_db.sql；
--   必须早于 07_align_all_schema.sql（后者会 MODIFY 本表列）。
--
-- 【依赖】
--   库 carbon_emission 已存在。本文件不含 USE，需用 -D carbon_emission 指定库。
--
-- 【幂等性】
--   CREATE TABLE IF NOT EXISTS：可重复执行；已存在时直接跳过，
--   不会覆盖或修改既有结构与数据。
-- ============================================================

-- 监管机构档案表
-- 约束：id 为机构编号（业务主键）；status 仅 active / inactive；
--       其余联系类字段可空，便于先建档后补全。
-- 字段分组：① 机构标识 ② 联系方式与职责 ③ 状态与成立时间
CREATE TABLE IF NOT EXISTS `regulator_organizations` (
  -- ① 机构标识
  `id`          VARCHAR(20)  NOT NULL              COMMENT '机构编号',
  `name`        VARCHAR(100) NOT NULL              COMMENT '机构名称',
  `region`      VARCHAR(50)           DEFAULT NULL COMMENT '所属区域',
  -- ② 联系方式与职责
  `contact`     VARCHAR(50)           DEFAULT NULL COMMENT '联系人',
  `phone`       VARCHAR(30)           DEFAULT NULL COMMENT '联系电话',
  `address`     VARCHAR(200)          DEFAULT NULL COMMENT '办公地址',
  `scope`       VARCHAR(200)          DEFAULT NULL COMMENT '监管职责',
  -- ③ 状态与成立时间
  `status`      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active 正常 / inactive 停用',
  `created_at`  DATE                  DEFAULT NULL COMMENT '成立时间',
  -- 主键：机构编号（业务编号）
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='监管机构表';
