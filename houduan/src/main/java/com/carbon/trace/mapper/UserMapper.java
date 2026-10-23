package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.User;

/**
 * 用户 Mapper。
 * 对应表 users（实体 {@link User}），继承 BaseMapper 获得基础 CRUD；
 * 账号唯一性、密码不回显等约束由业务层与实体序列化配置共同保证。
 */
public interface UserMapper extends BaseMapper<User> {
}
