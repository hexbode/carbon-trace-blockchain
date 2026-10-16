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
 */
@Configuration
@EnableConfigurationProperties(ChainProperties.class)
public class ChainGatewayConfig {

    /** 仅供链网关使用的 HTTP 客户端（不引 FISCO BCOS SDK / web3j，保证构建离线可用） */
    @Bean
    public RestTemplate chainRestTemplate(ChainProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getWebase().getTimeoutMs());
        factory.setReadTimeout(properties.getWebase().getTimeoutMs());
        return new RestTemplate(factory);
    }
}
