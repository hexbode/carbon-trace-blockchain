package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CBAM 排放数据版本（企业 + 年度唯一）。
 * 对应表 cbam_data_versions；任何排放记录新增/修改/删除都会使 version + 1，
 * 申报包生成时把当时的 version 写入 cbam_declarations.data_version。
 */
@Data
@TableName("cbam_data_versions")
public class CbamDataVersion {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业编号 */
    private String enterpriseId;

    /** 年度 */
    private Integer reportYear;

    /** 数据版本，排放记录变更时 +1 */
    private Long version;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
