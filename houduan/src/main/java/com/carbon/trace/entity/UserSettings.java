package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号设置表
 */
@Data
@TableName("user_settings")
public class UserSettings {

    /** 用户ID（与 users.id 一一对应） */
    @TableId(type = IdType.INPUT)
    private Integer userId;

    /** 站内消息通知 */
    private Boolean msgNotify;

    /** 短信通知 */
    private Boolean smsNotify;

    /** 异地登录提醒 */
    private Boolean loginRemind;

    /** 周报推送 */
    private Boolean weeklyReport;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
