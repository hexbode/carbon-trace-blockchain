package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CBAM 年度申报包（面向欧盟进口商的数据包快照）。
 * 对应表 cbam_declarations；同一企业同一年度唯一，重新生成时覆盖快照。
 * 状态机：draft → submitted → approved / returned（退回后可重新生成）。
 */
@Data
@TableName("cbam_declarations")
public class CbamDeclaration {

    /** 申报包主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申报包编号 CBAM-年度-序号 */
    private String declarationNo;

    /** 企业编号 */
    private String enterpriseId;

    /** 申报年度 */
    private Integer reportYear;

    /** 欧盟进口商名称 */
    private String importerName;

    /** 申报货物总量（吨） */
    private BigDecimal totalQuantity;

    /** 直接隐含排放合计 tCO2e */
    private BigDecimal directEmissions;

    /** 间接隐含排放合计 tCO2e */
    private BigDecimal indirectEmissions;

    /** 隐含排放合计 tCO2e */
    private BigDecimal totalEmissions;

    /** 已付碳价合计（欧元） */
    private BigDecimal carbonPrice;

    /** 完整性：complete / incomplete */
    private String completeness;

    /** 缺失项说明（分号分隔） */
    private String missingItems;

    /** 状态：draft/submitted/approved/returned */
    private String status;

    /** 监管确认意见 */
    private String reviewComment;

    /** 申报包 JSON 快照 */
    private String packageJson;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
