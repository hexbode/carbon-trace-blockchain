package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.UserSettings;

/**
 * 用户账号设置 Mapper。
 * 对应表 user_settings（实体 {@link UserSettings}），主键 userId 与 users.id 一一对应（Integer，非自增）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用操作为按 userId 查询、首次读取时插入默认值、
 * 按 userId 更新开关，以及删除用户前按 userId 批量清理本表记录以避免外键约束。
 */
public interface UserSettingsMapper extends BaseMapper<UserSettings> {
}
