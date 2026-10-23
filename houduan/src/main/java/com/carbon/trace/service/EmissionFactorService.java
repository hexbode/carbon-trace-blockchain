package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionFactor;

import java.util.List;

/**
 * 碳排放因子服务。
 * 维护排放因子库（因子值 + 单位 + 依据标准 + 启停状态），供排放核算按排放源/类别选取权威因子。
 */
public interface EmissionFactorService {

    /** 按类别、关键字过滤查询因子列表 */
    List<EmissionFactor> list(String category, String keyword);

    /** 按主键查询因子 */
    EmissionFactor getById(Integer id);

    /** 新增因子 */
    EmissionFactor create(EmissionFactor factor);

    /** 按主键更新因子 */
    EmissionFactor update(Integer id, EmissionFactor factor);

    /** 按主键删除因子 */
    void delete(Integer id);
}
