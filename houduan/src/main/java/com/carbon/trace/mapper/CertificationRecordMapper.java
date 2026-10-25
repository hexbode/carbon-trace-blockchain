package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.CertificationRecord;

/**
 * 认证记录 Mapper。
 * 对应表 certification_records（实体 {@link CertificationRecord}），主键 id 为 String 认证编号（CR + 6 位序号，
 * 与排放记录、报告记录三表同号）。
 * 继承 BaseMapper 获得基础 CRUD，无自定义 SQL；常用查询包括：按 enterpriseId/status 过滤、
 * 关键字匹配 id/enterpriseName/auditor 并按 certifiedAt 倒序；按 emissionId 反查认证记录；
 * 按 id 集合取公共端固定示例证书。
 * 存证的链上交易哈希/区块高度不落库，由业务层回填（见 CertificationServiceImpl）。
 */
public interface CertificationRecordMapper extends BaseMapper<CertificationRecord> {
}
