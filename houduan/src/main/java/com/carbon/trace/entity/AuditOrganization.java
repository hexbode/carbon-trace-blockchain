package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 审核机构表。
 * 对应表 audit_organizations，主键 id 为业务分配的机构编号（IdType.INPUT）。
 * 业务职责：登记具有碳排放认证资质的第三方审核机构；审核员账号（user.orgId）挂靠在本表机构下，对企业的排放记录出具认证。
 * 字段契约：{@code qualification} 为资质证书号，{@code scope} 为可承接的业务范围，配合 {@code status} 控制机构是否可入驻/承接审核。
 */
@Data
@TableName("audit_organizations")
public class AuditOrganization {

    /** 机构编号；应用内生成的唯一标识，审核员账号通过 orgId 引用。 */
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
