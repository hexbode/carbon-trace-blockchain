package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.UserSettings;

/**
 * 用户账号设置 Mapper。
 * 对应表 user_settings（实体 {@link UserSettings}），主键 userId 与 users.id 一一对应，
 * 继承 BaseMapper 获得基础 CRUD；删除用户前需先清理本表记录避免外键约束。
 */
public interface UserSettingsMapper extends BaseMapper<UserSettings> {
}
