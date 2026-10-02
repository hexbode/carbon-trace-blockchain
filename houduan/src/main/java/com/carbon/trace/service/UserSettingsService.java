package com.carbon.trace.service;

import java.util.Map;

/**
 * 用户账号设置服务
 */
public interface UserSettingsService {

    /** 获取账号设置，记录不存在时自动插入默认值（全部开启）后返回 */
    Map<String, Object> getSettings(Integer userId);

    /** 更新账号设置（不存在时先插入），返回最新设置 */
    Map<String, Object> updateSettings(Integer userId, Map<String, Object> body);
}
