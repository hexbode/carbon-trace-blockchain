package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionSource;

import java.util.List;

/**
 * 排放源服务。
 * 提供排放源字典查询，作为企业填报排放记录时的可选排放源与默认排放因子来源。
 */
public interface EmissionSourceService {

    /** 查询全部排放源 */
    List<EmissionSource> list();

    /** 按排放源编码查询 */
    EmissionSource getByCode(String code);
}
