package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.Alert;

/**
 * 异常告警 Mapper。
 * 对应表 alerts（实体 {@link Alert}），继承 MyBatis-Plus BaseMapper 获得基础 CRUD，
 * 无自定义 SQL；业务层通过 LambdaQueryWrapper/LambdaUpdateWrapper 组装条件。
 */
public interface AlertMapper extends BaseMapper<Alert> {
}
