package com.carbon.trace.chain.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 链网关装配。
 *
 * 具体实现按 carbon.chain.mode 选择：
 *  - WebaseChainGateway：@ConditionalOnProperty(mode=webase)
 *  - StubChainGateway  ：@ConditionalOnProperty(mode=stub, matchIfMissing=true)
 *
 * 本类只负责提供链网关专用的 HTTP 客户端与启用 {@link ChainProperties}；
 * 两个实现各自带条件注解，由 Spring 按配置二选一装配，因此不要在此再手写路由判断。
 *
 * 约束：RestTemplate 的 Bean 名称固定为 {@code chainRestTemplate}，
 * {@code WebaseClient} 通过构造参数名注入，改名会导致注入失败或误用其他 RestTemplate。
 */
@Configuration
@EnableConfigurationProperties(ChainProperties.class)
public class ChainGatewayConfig {

    /**
     * 仅供链网关使用的 HTTP 客户端（不引 FISCO BCOS SDK / web3j，保证构建离线可用）。
     *
     * 连接与读取超时共用 {@code carbon.chain.webase.timeout-ms}；链调用是同步阻塞的，
     * 超时设置过大会拖住 Web 线程，过小则会把慢区块误判为链不可用。
     *
     * @param properties 链配置，只读取 webase.timeout-ms
     * @return 无拦截器、无连接池的 {@link RestTemplate} 实例
     */
    @Bean
    public RestTemplate chainRestTemplate(ChainProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getWebase().getTimeoutMs());
        factory.setReadTimeout(properties.getWebase().getTimeoutMs());
        return new RestTemplate(factory);
    }
}
