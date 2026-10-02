package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 监管机构表（监管端）
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
