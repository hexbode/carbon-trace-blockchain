package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 企业信息表。
 * 对应表 enterprises，主键 id 为业务分配的企业编号（IdType.INPUT）。
 * 业务职责：登记纳入碳排放核算范围的控排企业，企业用户账号（user.enterpriseId）挂靠其下填报排放数据；
 * {@code creditCode} 为社会信用代码等法定标识，{@code scope} 描述企业核算边界，{@code status} 控制是否可继续填报。
 * 关联关系：users.enterprise_id 引用本表 id（enterprise 角色）；排放记录、报告记录、认证记录均冗余企业名称，
 * 并通过 enterprise_id 归属到本表。
 */
@Data
@TableName("enterprises")
public class Enterprise {

    /** 企业编号；应用内生成的唯一标识（格式 ENT + 年份 + 4 位序号），企业用户账号通过 enterpriseId 引用。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业名称 */
    private String name;

    /** 所属行业；用于监管端行业聚合统计与筛选。 */
    private String industry;

    /** 统一社会信用代码 */
    private String creditCode;

    /** 联系人 */
    private String contact;

    /** 联系电话 */
    private String phone;

    /** 注册地址 */
    private String address;

    /** 核算范围；描述企业纳入碳排放核算的业务/组织边界（账户管辖口径）。 */
    private String scope;

    /** 注册时间 */
    private LocalDate registeredAt;

    /** 状态：active 正常 / inactive 停用 */
    private String status;
}
