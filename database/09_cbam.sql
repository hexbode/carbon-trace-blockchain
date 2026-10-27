-- =============================================================
-- 09_cbam.sql —— CBAM 合规能力（P0）建表与演示种子数据
--
-- 【用途】
--   补全 CBAM 正式期（2026-01-01 起）要求的“装置/产品级隐含排放 +
--   核查报告 + 年度申报包”能力，与现有企业级排放台账互补。
--
-- 【执行顺序】
--   最后一位，晚于 carbon_emission_db.sql（依赖 enterprises / users 已存在）。
--
-- 【幂等性】
--   可重复执行：CREATE TABLE IF NOT EXISTS；种子数据使用 INSERT IGNORE。
--
-- 【注意】
--   * 默认排放值为演示数据，不是欧盟官方默认值（官方数值见
--     CBAM Default values definitive period 附件，正式使用前需按行业导入）；
--   * 本脚本不修改既有 11 张业务表，只新增 6 张 CBAM 表。
-- =============================================================
USE `carbon_emission`;

-- 1. CBAM 产品目录（CN 编码 + 行业 + 示例默认值）
CREATE TABLE IF NOT EXISTS `cbam_goods` (
  `id`               INT          NOT NULL AUTO_INCREMENT COMMENT '产品ID',
  `cn_code`          VARCHAR(20)  NOT NULL COMMENT 'CN 商品编码',
  `name`             VARCHAR(100) NOT NULL COMMENT '产品名称',
  `sector`           VARCHAR(30)  NOT NULL COMMENT 'CBAM 行业：cement/steel/aluminium/fertiliser/electricity/hydrogen',
  `default_direct`   DECIMAL(14,4) DEFAULT NULL COMMENT '示例默认直接排放强度 tCO2e/t（演示数据）',
  `default_indirect` DECIMAL(14,4) DEFAULT NULL COMMENT '示例默认间接排放强度 tCO2e/t（演示数据）',
  `status`           VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `created_at`       DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_goods_cn` (`cn_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 产品目录';

-- 2. CBAM 生产装置
CREATE TABLE IF NOT EXISTS `cbam_installations` (
  `id`               INT          NOT NULL AUTO_INCREMENT COMMENT '装置ID',
  `enterprise_id`    VARCHAR(20)  NOT NULL COMMENT '所属企业编号',
  `name`             VARCHAR(100) NOT NULL COMMENT '装置名称',
  `country`          VARCHAR(50)  NOT NULL DEFAULT 'CN' COMMENT '装置所在国家',
  `production_route` VARCHAR(100) DEFAULT NULL COMMENT '生产工艺路线，如 BF-BOF / EAF',
  `address`          VARCHAR(200) DEFAULT NULL COMMENT '装置地址',
  `status`           VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `created_at`       DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_cbam_install_ent` (`enterprise_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 生产装置';

-- 3. 产品级隐含排放（年度、装置、产品维度）
CREATE TABLE IF NOT EXISTS `cbam_product_emissions` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `enterprise_id`      VARCHAR(20)   NOT NULL COMMENT '企业编号',
  `report_year`        INT           NOT NULL COMMENT '报告年度',
  `goods_id`           INT           NOT NULL COMMENT 'CBAM 产品ID',
  `installation_id`    INT           NOT NULL COMMENT '生产装置ID',
  `quantity`           DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '年度产量（吨）',
  `data_method`        VARCHAR(20)   NOT NULL DEFAULT 'actual' COMMENT '数据方式：actual 实测 / default 默认值',
  `direct_intensity`   DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '直接排放强度 tCO2e/t',
  `indirect_intensity` DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '间接排放强度 tCO2e/t',
  `direct_emissions`   DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '直接隐含排放总量 tCO2e',
  `indirect_emissions` DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '间接隐含排放总量 tCO2e',
  `carbon_price`       DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已付碳价（欧元）',
  `remark`             VARCHAR(300)  DEFAULT NULL COMMENT '备注',
  `created_at`         DATETIME      DEFAULT NULL COMMENT '创建时间',
  `updated_at`         DATETIME      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_cbam_em_ent_year` (`enterprise_id`, `report_year`),
  KEY `idx_cbam_em_goods` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 产品级隐含排放';

-- 4. 核查机构（欧盟 NAB 认可）
CREATE TABLE IF NOT EXISTS `cbam_verifiers` (
  `id`               INT          NOT NULL AUTO_INCREMENT COMMENT '核查机构ID',
  `name`             VARCHAR(100) NOT NULL COMMENT '机构名称',
  `accreditation_no` VARCHAR(50)  NOT NULL COMMENT '认可编号',
  `nab`              VARCHAR(50)  DEFAULT NULL COMMENT '国家认可机构（NAB）',
  `country`          VARCHAR(50)  DEFAULT NULL COMMENT '机构所在国家',
  `status`           VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `created_at`       DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_verifier_acc` (`accreditation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 核查机构';

-- 5. 核查报告（装置级、年度）
CREATE TABLE IF NOT EXISTS `cbam_verification_reports` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '核查报告ID',
  `enterprise_id`   VARCHAR(20)  NOT NULL COMMENT '企业编号',
  `installation_id` INT          NOT NULL COMMENT '装置ID',
  `report_year`     INT          NOT NULL COMMENT '核查年度',
  `verifier_id`     INT          NOT NULL COMMENT '核查机构ID',
  `report_no`       VARCHAR(50)  NOT NULL COMMENT '核查报告编号',
  `conclusion`      VARCHAR(20)  NOT NULL DEFAULT 'verified' COMMENT '结论：verified / not_verified',
  `issued_at`       DATE         DEFAULT NULL COMMENT '出具日期',
  `remark`          VARCHAR(300) DEFAULT NULL COMMENT '备注',
  `created_at`      DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_vr` (`enterprise_id`, `installation_id`, `report_year`, `report_no`),
  KEY `idx_cbam_vr_ent_year` (`enterprise_id`, `report_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 核查报告';

-- 6. 年度申报包（面向欧盟进口商的数据包）
CREATE TABLE IF NOT EXISTS `cbam_declarations` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '申报包ID',
  `declaration_no`   VARCHAR(30)   NOT NULL COMMENT '申报包编号 CBAM-年度-序号',
  `enterprise_id`    VARCHAR(20)   NOT NULL COMMENT '企业编号',
  `report_year`      INT           NOT NULL COMMENT '申报年度',
  `importer_name`    VARCHAR(100)  DEFAULT NULL COMMENT '欧盟进口商名称',
  `total_quantity`   DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '申报货物总量（吨）',
  `direct_emissions` DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '直接隐含排放合计 tCO2e',
  `indirect_emissions` DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '间接隐含排放合计 tCO2e',
  `total_emissions`  DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '隐含排放合计 tCO2e',
  `carbon_price`     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已付碳价合计（欧元）',
  `completeness`     VARCHAR(20)   NOT NULL DEFAULT 'incomplete' COMMENT '完整性：complete / incomplete',
  `missing_items`    VARCHAR(600)  DEFAULT NULL COMMENT '缺失项说明',
  `status`           VARCHAR(20)   NOT NULL DEFAULT 'draft' COMMENT '状态：draft/submitted/approved/returned',
  `review_comment`   VARCHAR(300)  DEFAULT NULL COMMENT '监管确认意见',
  `package_json`     LONGTEXT      DEFAULT NULL COMMENT '申报包快照（JSON）',
  `created_at`       DATETIME      DEFAULT NULL COMMENT '创建时间',
  `updated_at`       DATETIME      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cbam_decl` (`enterprise_id`, `report_year`),
  KEY `idx_cbam_decl_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CBAM 年度申报包';

-- =============================================================
-- 演示种子数据（INSERT IGNORE，可重复执行）
-- 默认排放强度为演示数值，正式使用前请按欧盟 2026 默认值附件替换。
-- =============================================================
INSERT IGNORE INTO `cbam_goods` (`id`, `cn_code`, `name`, `sector`, `default_direct`, `default_indirect`, `created_at`) VALUES
  (1, '2523 29 00', '硅酸盐水泥（其他）', 'cement', 0.8600, 0.4200, NOW()),
  (2, '7208 51 00', '热轧钢板', 'steel', 2.1000, 0.0000, NOW()),
  (3, '7601 10 00', '未锻轧原铝', 'aluminium', 1.6000, 0.0000, NOW()),
  (4, '3102 10 10', '尿素', 'fertiliser', 0.9000, 0.3000, NOW()),
  (5, '2804 10 00', '氢气', 'hydrogen', 9.0000, 0.0000, NOW()),
  (6, '2716 00 00', '电力', 'electricity', 0.0000, 0.5000, NOW());

INSERT IGNORE INTO `cbam_verifiers` (`id`, `name`, `accreditation_no`, `nab`, `country`, `created_at`) VALUES
  (1, 'TÜV SÜD CBAM 核查机构（示例）', 'CBAM-DE-0001', 'DAkkS', 'DE', NOW());

INSERT IGNORE INTO `cbam_installations` (`id`, `enterprise_id`, `name`, `country`, `production_route`, `address`, `created_at`) VALUES
  (1, 'ENT20240001', '华东钢铁一号生产装置', 'CN', 'BF-BOF', '上海市宝山区示例路 1 号', NOW());

INSERT IGNORE INTO `cbam_verification_reports`
  (`id`, `enterprise_id`, `installation_id`, `report_year`, `verifier_id`, `report_no`, `conclusion`, `issued_at`, `remark`, `created_at`) VALUES
  (1, 'ENT20240001', 1, 2026, 1, 'VR-2026-0001', 'verified', '2026-06-30', '演示核查报告', NOW());

INSERT IGNORE INTO `cbam_product_emissions`
  (`id`, `enterprise_id`, `report_year`, `goods_id`, `installation_id`, `quantity`, `data_method`,
   `direct_intensity`, `indirect_intensity`, `direct_emissions`, `indirect_emissions`, `carbon_price`, `remark`, `created_at`, `updated_at`) VALUES
  (1, 'ENT20240001', 2026, 2, 1, 1200.000, 'actual',
   2.1000, 0.0000, 2520.000, 0.000, 0.00, '演示数据：热轧钢板实际排放', NOW(), NOW());
