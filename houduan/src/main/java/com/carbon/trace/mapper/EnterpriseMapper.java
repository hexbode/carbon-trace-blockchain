package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.Enterprise;

/**
 * 企业 Mapper。
 * 对应表 enterprises（实体 {@link Enterprise}），继承 BaseMapper 获得基础 CRUD，无自定义 SQL。
 */
public interface EnterpriseMapper extends BaseMapper<Enterprise> {
}
