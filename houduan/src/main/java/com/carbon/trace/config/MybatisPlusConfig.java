package com.carbon.trace.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件（列表接口不分页，保留以备后用）。
 *
 * 安全边界：此处只装配分页插件，未启用多租户 / 数据权限类插件，
 * 因此企业维度的数据隔离完全由业务层（UserScopes + 查询条件）保证，不能依赖 ORM 自动过滤。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 组装 MyBatis-Plus 拦截器链并注册 MySQL 方言分页插件（当前列表接口未启用分页，保留以备后续功能使用）。
     *
     * @return 已装配分页内部拦截器的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
