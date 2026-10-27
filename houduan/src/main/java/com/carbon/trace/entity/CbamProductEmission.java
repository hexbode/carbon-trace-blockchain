package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CBAM 产品级隐含排放记录（年度 × 产品 × 装置）。
 * 对应表 cbam_product_emissions；排放总量由服务端按“产量 × 强度”计算后落库，
 * 不信任前端提交的汇总值。
 */
@Data
@TableName("cbam_product_emissions")
public class CbamProductEmission {

    /** 记录主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业编号 */
    private String enterpriseId;

    /** 报告年度 */
    private Integer reportYear;

    /** CBAM 产品ID，关联 cbam_goods.id */
    private Integer goodsId;

    /** 生产装置ID，关联 cbam_installations.id */
    private Integer installationId;

    /** 年度产量（吨） */
    private BigDecimal quantity;

    /** 数据方式：actual 实测 / default 默认值 */
    private String dataMethod;

    /** 直接排放强度 tCO2e/t */
    private BigDecimal directIntensity;

    /** 间接排放强度 tCO2e/t */
    private BigDecimal indirectIntensity;

    /** 直接隐含排放总量 tCO2e */
    private BigDecimal directEmissions;

    /** 间接隐含排放总量 tCO2e */
    private BigDecimal indirectEmissions;

    /** 已付碳价（欧元） */
    private BigDecimal carbonPrice;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
