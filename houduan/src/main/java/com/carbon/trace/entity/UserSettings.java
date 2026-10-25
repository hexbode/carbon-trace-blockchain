package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号设置表。
 * 对应表 user_settings，主键 userId 与 users.id 一一对应（非自增，IdType.INPUT）。
 * 业务职责：保存单个用户的站内通知偏好（消息/短信提醒、异地登录提醒、周报推送等开关），
 * 由告警推送、周报任务等读取决定是否触达用户；无独立业务主键，生命周期跟随所属用户。
 * 默认约定：首次读取且无记录时按「全部开启」插入一行；删除用户前需先清理本表记录。
 */
@Data
@TableName("user_settings")
public class UserSettings {

    /** 用户ID（与 users.id 一一对应） */
    @TableId(type = IdType.INPUT)
    private Integer userId;

    /** 站内消息通知开关：true 开启 / false 关闭。 */
    private Boolean msgNotify;

    /** 短信通知开关：true 开启 / false 关闭。 */
    private Boolean smsNotify;

    /** 异地登录提醒开关：true 开启 / false 关闭。 */
    private Boolean loginRemind;

    /** 周报推送开关：true 开启 / false 关闭。 */
    private Boolean weeklyReport;

    /** 更新时间；每次更新设置时刷新。 */
    private LocalDateTime updatedAt;
}
