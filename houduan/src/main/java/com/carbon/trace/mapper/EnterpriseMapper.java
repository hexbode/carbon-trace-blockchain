package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.Enterprise;

/**
 * 企业 Mapper。
 * 对应表 enterprises（实体 {@link Enterprise}），主键 id 为 String 企业编号（ENT + 年份 + 4 位序号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用操作为按 name 模糊查询、按 industry 精确过滤
 * 并按 id 升序，以及新增前 selectCount 统计总数用于生成编号。
 */
public interface EnterpriseMapper extends BaseMapper<Enterprise> {
}
