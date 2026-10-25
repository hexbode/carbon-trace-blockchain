package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionSource;

import java.util.List;

/**
 * 排放源服务。
 * 提供排放源字典查询，作为企业填报排放记录时的可选排放源与默认排放因子来源。
 * 只读服务：不写库、不写链，方法无事务语义；权限为登录即可（Controller 未额外限制角色）。
 */
public interface EmissionSourceService {

    /**
     * 查询全部排放源，按编码升序。
     *
     * @return 排放源字典列表；无数据时返回空列表
     */
    List<EmissionSource> list();

    /**
     * 按排放源编码查询单个排放源。
     * 编码不存在时返回 null（由调用方决定是否抛 404），不产生异常。
     *
     * @param code 排放源编码（emission_sources.code）
     * @return 排放源实体，不存在时为 null
     */
    EmissionSource getByCode(String code);
}
