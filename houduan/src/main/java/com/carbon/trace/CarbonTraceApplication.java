package com.carbon.trace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类：企业碳排放数据认证与追溯系统后端。
 * 装配说明：
 *  - {@code @SpringBootApplication} 聚合组件扫描与自动配置；
 *  - {@code @EnableScheduling} 开启定时任务（如告警/链上完整性巡检等周期任务）；
 *  - {@code @MapperScan("com.carbon.trace.mapper")} 批量注册 MyBatis-Plus Mapper 接口；
 *  - 主流程仅为委托 {@code SpringApplication.run} 引导容器启动，无额外业务逻辑。
 *
 * 安全边界：本类不承载任何鉴权逻辑；登录态校验由 AuthInterceptor 统一在 /api/** 前置完成，
 * 角色与企业归属校验由各 Controller / UserScopes 完成。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.carbon.trace.mapper")
public class CarbonTraceApplication {

    /**
     * 应用启动入口：引导 Spring Boot 容器完成组件扫描、自动配置、Mapper 注册与内嵌 Web 容器启动。
     * 启动过程不执行业务逻辑；数据初始化（密码迁移等）由 ApplicationRunner 在容器就绪后执行。
     *
     * @param args 命令行参数，原样透传给 SpringApplication（可用于覆盖配置项，如 --server.port）
     */
    public static void main(String[] args) {
        SpringApplication.run(CarbonTraceApplication.class, args);
    }
}
