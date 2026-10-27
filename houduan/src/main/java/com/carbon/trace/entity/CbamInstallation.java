package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CBAM 生产装置（承载 CBAM 产品生产、核查与申报的最小单元）。
 * 对应表 cbam_installations，按企业编号归属。
 */
@Data
@TableName("cbam_installations")
public class CbamInstallation {

    /** 装置主键，自增 */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 所属企业编号 */
    private String enterpriseId;

    /** 装置名称 */
    private String name;

    /** 装置所在国家，默认 CN */
    private String country;

    /** 生产工艺路线，如 BF-BOF / EAF */
    private String productionRoute;

    /** 装置地址 */
    private String address;

    /** 状态：active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
