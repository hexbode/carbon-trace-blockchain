package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CBAM 核查机构：须为经欧盟国家认可机构（NAB）认可的核查机构。
 * 对应表 cbam_verifiers，认可编号唯一。
 */
@Data
@TableName("cbam_verifiers")
public class CbamVerifier {

    /** 核查机构主键，自增 */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 机构名称 */
    private String name;

    /** 认可编号，唯一 */
    private String accreditationNo;

    /** 国家认可机构（NAB） */
    private String nab;

    /** 机构所在国家 */
    private String country;

    /** 状态：active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
