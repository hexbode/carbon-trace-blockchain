package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 审核机构表
 */
@Data
@TableName("audit_organizations")
public class AuditOrganization {

    /** 机构编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 机构名称 */
    private String name;

    /** 机构简称 */
    private String shortName;

    /** 机构代码 */
    private String code;

    /** 联系人 */
    private String contact;

    /** 联系电话 */
    private String phone;

    /** 资质证书号 */
    private String qualification;

    /** 业务范围 */
    private String scope;

    /** 状态 */
    private String status;

    /** 入驻时间 */
    private LocalDate createdAt;
}
