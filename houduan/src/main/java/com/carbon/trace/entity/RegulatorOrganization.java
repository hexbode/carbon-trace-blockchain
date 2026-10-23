package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 监管机构表。
 * 对应表 regulator_organizations，主键 id 为业务分配的机构编号（IdType.INPUT）。
 * 业务职责：登记具有碳排放监管职权的政府部门/监管单位，监管员账号（user.orgId）挂靠其下，
 * 对本辖区企业进行合规监管与告警处置；{@code region} 划分辖区，{@code scope} 描述监管职责边界。
 */
@Data
@TableName("regulator_organizations")
public class RegulatorOrganization {

    /** 机构编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 机构名称 */
    private String name;

    /** 所属区域 */
    private String region;

    /** 联系人 */
    private String contact;

    /** 联系电话 */
    private String phone;

    /** 办公地址 */
    private String address;

    /** 监管职责 */
    private String scope;

    /** 状态 */
    private String status;

    /** 成立时间 */
    private LocalDate createdAt;
}
