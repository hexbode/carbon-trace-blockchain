package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.mapper.EnterpriseMapper;
import com.carbon.trace.service.EnterpriseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.carbon.trace.common.ApiException;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 企业服务实现。
 * 无事务边界（单表操作）；新增时编号空则按「ENT + 年份 + 4 位序号」生成，注册时间默认当日；
 * 行业列表从企业数据内存内去重排序，供前端筛选下拉。
 */
@Service
public class EnterpriseServiceImpl implements EnterpriseService {

    private final EnterpriseMapper enterpriseMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param enterpriseMapper 企业 Mapper，本类全部查询与增删改都经由此处
     */
    public EnterpriseServiceImpl(EnterpriseMapper enterpriseMapper) {
        this.enterpriseMapper = enterpriseMapper;
    }

    /**
     * 按企业名称模糊、行业精确过滤企业列表，按企业编号升序返回。
     *
     * 权限：控制器仅要求登录，服务层不鉴权。
     *
     * @param name     企业名称关键字；空白时不参与过滤
     * @param industry 行业名称；空白时不参与过滤
     * @return 企业列表
     */
    @Override
    public List<Enterprise> list(String name, String industry) {
        LambdaQueryWrapper<Enterprise> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(Enterprise::getName, name);
        }
        if (StringUtils.hasText(industry)) {
            wrapper.eq(Enterprise::getIndustry, industry);
        }
        wrapper.orderByAsc(Enterprise::getId);
        return enterpriseMapper.selectList(wrapper);
    }

    /**
     * 汇总已登记企业中出现过的行业（去重、升序），供前端筛选下拉复用。
     *
     * 每次全表读取后在内存去重并过滤 null，未做缓存。
     *
     * @return 行业名称列表；无企业数据时为空列表
     */
    @Override
    public List<String> industries() {
        return enterpriseMapper.selectList(null).stream()
                .map(Enterprise::getIndustry)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 按企业编号查询企业详情。
     *
     * @param id 企业编号（业务主键）
     * @return 企业实体
     * @throws ApiException 企业不存在时抛 404（NOT_FOUND）
     */
    @Override
    public Enterprise getById(String id) {
        Enterprise enterprise = enterpriseMapper.selectById(id);
        if (enterprise == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "企业不存在");
        }
        return enterprise;
    }

    /**
     * 新增企业：编号空则自动生成，状态默认 active，注册时间默认当日。
     *
     * 编号规则为「ENT + 当前年份 + 4 位序号」，序号取当前记录总数 + 1：
     * 删除历史记录后重算会与已有编号撞号，并发新增也可能生成相同编号，两种情况均由主键冲突兜底；
     * 调用方也可直接提交自定义编号以绕过该规则。
     * credit_code 由数据库唯一键（uk_enterprise_credit）保证唯一，重复时插入异常上抛。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param enterprise 企业实体（name、industry、creditCode 为库表非空字段）
     * @return 落库后的企业实体
     */
    @Override
    public Enterprise create(Enterprise enterprise) {
        if (!StringUtils.hasText(enterprise.getId())) {
            long count = enterpriseMapper.selectCount(null);
            enterprise.setId("ENT" + Year.now().getValue() + String.format("%04d", count + 1));
        }
        if (!StringUtils.hasText(enterprise.getStatus())) {
            enterprise.setStatus("active");
        }
        // 注册时间由后端补齐为创建当天
        if (enterprise.getRegisteredAt() == null) {
            enterprise.setRegisteredAt(LocalDate.now());
        }
        enterpriseMapper.insert(enterprise);
        return enterprise;
    }

    /**
     * 按编号更新企业档案。
     *
     * 以路径编号覆盖请求体编号，防止借更新接口改写其他企业；
     * MyBatis-Plus 默认忽略 null 字段，未提交的字段保持原值（本方法无法把字段显式置空）；
     * 更新后回查最新数据，同时把「更新不存在的记录」暴露为 404 而不是静默成功。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id         企业编号（以路径参数为准）
     * @param enterprise 待更新字段
     * @return 更新后的企业实体
     * @throws ApiException 企业不存在时抛 404
     */
    @Override
    public Enterprise update(String id, Enterprise enterprise) {
        enterprise.setId(id);
        enterpriseMapper.updateById(enterprise);
        return getById(id);
    }

    /**
     * 按编号删除企业。
     *
     * 先校验存在性，避免删除不存在的编号时静默成功；
     * 企业被用户账号、排放记录、报告/认证记录或告警以级联外键引用时，删除会被数据库拒绝，本方法不做引用预检。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id 企业编号
     * @throws ApiException 企业不存在时抛 404
     */
    @Override
    public void delete(String id) {
        // 先确认记录存在，避免删除不存在的记录时静默成功
        getById(id);
        enterpriseMapper.deleteById(id);
    }
}
