package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.CbamOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/** CBAM Outbox Mapper。 */
@Mapper
public interface CbamOutboxMapper extends BaseMapper<CbamOutboxEvent> {
}
