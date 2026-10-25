package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.User;

/**
 * 用户 Mapper。
 * 对应表 users（实体 {@link User}），主键 id 为数据库自增 Integer。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用查询包括：按 username 模糊匹配、按 role 精确过滤
 * 并按 id 升序；登录时按 username 精确查询（limit 1）。
 * 账号唯一性、密码不回显等约束由业务层与实体序列化配置共同保证。
 */
public interface UserMapper extends BaseMapper<User> {
}
