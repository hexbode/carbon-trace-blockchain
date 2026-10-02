package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 系统用户表
 */
@Data
@TableName("users")
public class User {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 登录账号 */
    private String username;

    /** 登录密码（演示用明文） */
    private String password;

    /** 姓名 */
    private String name;

    /** 角色：enterprise/auditor/regulator/admin */
    private String role;

    /** 所属企业（企业用户） */
    private String enterpriseId;

    /** 所属机构（审核人员） */
    private String orgId;

    /** 联系电话 */
    private String phone;

    /** 电子邮箱 */
    private String email;

    /** 个性签名 */
    private String signature;

    /** 头像地址（上传后的图片相对路径，如 /uploads/avatar/xxx.jpg） */
    private String avatar;

    /** 状态 */
    private String status;

    /** 创建时间 */
    private LocalDate createdAt;
}
