package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CBAM 产品目录（CN 编码 + 行业 + 示例默认值）。
 * 对应表 cbam_goods；默认排放强度仅用于演示，正式使用前应按欧盟 2026 默认值附件替换。
 */
@Data
@TableName("cbam_goods")
public class CbamGoods {

    /** 产品主键，自增 */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** CN 商品编码，唯一 */
    private String cnCode;

    /** 产品名称 */
    private String name;

    /** CBAM 行业：cement/steel/aluminium/fertiliser/electricity/hydrogen */
    private String sector;

    /** 示例默认直接排放强度 tCO2e/t */
    private BigDecimal defaultDirect;

    /** 示例默认间接排放强度 tCO2e/t */
    private BigDecimal defaultIndirect;

    /** 状态：active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
