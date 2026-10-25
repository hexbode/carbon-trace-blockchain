package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.EmissionFactor;
import com.carbon.trace.mapper.EmissionFactorMapper;
import com.carbon.trace.service.EmissionFactorService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 碳排放因子服务实现。
 * 无事务边界（单表操作）；新增时自动补主键（当前总数+1）、默认启用状态与更新时间，更新时刷新更新时间。
 */
@Service
public class EmissionFactorServiceImpl implements EmissionFactorService {

    private final EmissionFactorMapper emissionFactorMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param emissionFactorMapper 排放因子 Mapper，本类全部查询与增删改都经由此处
     */
    public EmissionFactorServiceImpl(EmissionFactorMapper emissionFactorMapper) {
        this.emissionFactorMapper = emissionFactorMapper;
    }

    /**
     * 按类别精确、关键字模糊过滤排放因子列表，按主键升序返回。
     *
     * keyword 只匹配排放源名称（source），不检索单位与依据标准；
     * 查询未按 status 过滤，启用与停用的因子都会返回。
     *
     * 权限：控制器仅要求登录，服务层不鉴权。
     *
     * @param category 类别（化石燃料/购入能源/工业过程等）；空白时不参与过滤
     * @param keyword  排放源名称关键字；空白时不参与过滤
     * @return 排放因子列表
     */
    @Override
    public List<EmissionFactor> list(String category, String keyword) {
        LambdaQueryWrapper<EmissionFactor> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            wrapper.eq(EmissionFactor::getCategory, category);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(EmissionFactor::getSource, keyword);
        }
        wrapper.orderByAsc(EmissionFactor::getId);
        return emissionFactorMapper.selectList(wrapper);
    }

    /**
     * 按主键查询排放因子，供核算取值与写操作的存在性校验。
     *
     * @param id 因子主键
     * @return 因子实体
     * @throws ApiException 因子不存在时抛 404
     */
    @Override
    public EmissionFactor getById(Integer id) {
        EmissionFactor factor = emissionFactorMapper.selectById(id);
        if (factor == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放因子不存在");
        }
        return factor;
    }

    /**
     * 新增排放因子：主键空则自动生成，状态默认 active，更新时间默认当日。
     *
     * 主键取当前记录总数 + 1（int），删除记录后重算会与已有主键冲突、并发新增也可能撞号，
     * 由主键约束兜底；调用方也可提交显式主键。
     * 因子库对「排放源 + 类别」没有唯一约束，允许登记重复口径的因子，本方法不做去重校验；
     * source/category/factor/unit 为库表非空字段，仅依赖数据库约束。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param factor 排放因子实体
     * @return 落库后的因子实体
     */
    @Override
    public EmissionFactor create(EmissionFactor factor) {
        if (factor.getId() == null) {
            long count = emissionFactorMapper.selectCount(null);
            factor.setId((int) count + 1);
        }
        if (!StringUtils.hasText(factor.getStatus())) {
            factor.setStatus("active");
        }
        // 更新时间由后端补齐为创建当天
        if (factor.getUpdatedAt() == null) {
            factor.setUpdatedAt(LocalDate.now());
        }
        emissionFactorMapper.insert(factor);
        return factor;
    }

    /**
     * 按主键更新排放因子，并把更新时间刷新为当天。
     *
     * 以路径主键覆盖请求体主键，防止改写其他因子；updatedAt 由服务端强制覆盖，忽略请求值；
     * 其余字段遵循 MyBatis-Plus 默认策略，null 不更新（保持原值）。更新后回查，不存在时抛 404。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id     因子主键（以路径参数为准）
     * @param factor 待更新字段
     * @return 更新后的因子实体
     * @throws ApiException 因子不存在时抛 404
     */
    @Override
    public EmissionFactor update(Integer id, EmissionFactor factor) {
        factor.setId(id);
        // 编辑时刷新更新时间为当天
        factor.setUpdatedAt(LocalDate.now());
        emissionFactorMapper.updateById(factor);
        return getById(id);
    }

    /**
     * 按主键删除排放因子。
     *
     * 先校验存在性，避免静默成功；因子仅被排放记录作为快照引用（emission_factor 为数值列，
     * 无外键），因此删除不影响历史记录的核算结果，本方法不做引用预检。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id 因子主键
     * @throws ApiException 因子不存在时抛 404
     */
    @Override
    public void delete(Integer id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        emissionFactorMapper.deleteById(id);
    }
}
