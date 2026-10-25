package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionFactor;

import java.util.List;

/**
 * 碳排放因子服务。
 * 维护排放因子库（因子值 + 单位 + 依据标准 + 启停状态），供排放核算按排放源/类别选取权威因子。
 * 对应表 emission_factors，均为单表操作，方法本身不开启事务、无链上副作用。
 * 权限：查询登录即可；新增/修改/删除由 Controller 限制为 regulator / admin。
 */
public interface EmissionFactorService {

    /**
     * 按类别、关键字过滤查询因子列表，按主键升序。
     *
     * @param category 因子类别（化石燃料/购入能源/工业过程），为空表示不过滤
     * @param keyword  排放源名称关键字，为空表示不过滤
     * @return 因子列表；无匹配时返回空列表
     */
    List<EmissionFactor> list(String category, String keyword);

    /**
     * 按主键查询因子。
     *
     * @param id 因子主键
     * @return 因子实体
     * @throws com.carbon.trace.common.ApiException 因子不存在时抛出 404
     */
    EmissionFactor getById(Integer id);

    /**
     * 新增因子；主键为空时按当前记录总数 + 1 生成，状态默认 active，更新时间默认当日。
     * 权限：仅 regulator / admin。
     *
     * @param factor 因子数据（排放源、类别、因子值、单位、依据标准等）
     * @return 落库后的因子实体
     */
    EmissionFactor create(EmissionFactor factor);

    /**
     * 按主键更新因子，并刷新更新时间为当日。
     * 权限：仅 regulator / admin。
     *
     * @param id     因子主键
     * @param factor 新的因子数据
     * @return 更新后的因子实体
     * @throws com.carbon.trace.common.ApiException 因子不存在时抛出 404
     */
    EmissionFactor update(Integer id, EmissionFactor factor);

    /**
     * 按主键删除因子。
     * 仅确认记录存在，不校验是否被历史排放记录引用；权限：仅 regulator / admin。
     *
     * @param id 因子主键
     * @throws com.carbon.trace.common.ApiException 因子不存在时抛出 404
     */
    void delete(Integer id);
}
