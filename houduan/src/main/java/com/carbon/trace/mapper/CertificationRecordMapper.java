package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.CertificationRecord;

/**
 * 认证记录 Mapper。
 * 对应表 certification_records（实体 {@link CertificationRecord}），继承 BaseMapper 获得基础 CRUD；
 * 存证的链上交易哈希/区块高度不落库，由业务层回填（见 CertificationServiceImpl）。
 */
public interface CertificationRecordMapper extends BaseMapper<CertificationRecord> {
}
