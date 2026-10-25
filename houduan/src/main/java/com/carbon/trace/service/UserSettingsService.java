package com.carbon.trace.service;

import java.util.Map;

/**
 * 用户账号设置服务。
 * 保存并读取单个用户的通知偏好（站内消息、短信、异地登录提醒、周报推送四个开关）；
 * 记录不存在时按默认值（全部开启）自动补建，保证首次访问即返回完整设置。
 * 仅单表读写，无显式事务与链上副作用。
 * 权限：Controller 允许本人或 regulator / admin 操作指定 userId 的设置。
 */
public interface UserSettingsService {

    /**
     * 获取账号设置；记录不存在时自动插入默认值（全部开启）后返回。
     *
     * @param userId 用户主键（users.id）
     * @return 包含 userId 与 4 个布尔开关的 Map
     */
    Map<String, Object> getSettings(Integer userId);

    /**
     * 更新账号设置（不存在时先插入默认值），并刷新更新时间。
     * 请求体中无法解析为布尔值的字段沿用原值，缺失字段保持不变。
     *
     * @param userId 用户主键（users.id）
     * @param body   开关字段（msgNotify/smsNotify/loginRemind/weeklyReport）
     * @return 更新后的设置 Map
     */
    Map<String, Object> updateSettings(Integer userId, Map<String, Object> body);
}
