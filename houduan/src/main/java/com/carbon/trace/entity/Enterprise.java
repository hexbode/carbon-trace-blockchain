package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 企业信息表
 */
@Data
@TableName("enterprises")
public class Enterprise {

    /** 企业编号 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 企业名称 */
    private String name;

    /** 所属行业 */
    private String industry;

    /** 统一社会信用代码 */
    private String creditCode;

    /** 联系人 */
    private String contact;

    /** 联系电话 */
    private String phone;

    /** 注册地址 */
    private String address;

    /** 核算范围 */
    private String scope;

    /** 注册时间 */
    private LocalDate registeredAt;

    /** 状态：active 正常 / inactive 停用 */
    private String status;
}
