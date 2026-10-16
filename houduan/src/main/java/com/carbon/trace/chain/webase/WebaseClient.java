package com.carbon.trace.chain.webase;

import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WeBASE-Front REST 客户端：统一 URL 拼装、超时与错误映射。
 *
 * 返回值统一为 {@link JsonNode}，兼容形状：
 *  - JSON 数组：只读（view/pure）方法解码后的返回值列表；
 *  - JSON 对象：写方法的交易回执，或事件/区块等结构化数据；
 *  - 裸字符串（如部署返回的合约地址）：包装为文本节点。
 */
@Component
public class WebaseClient {

    private static final Logger log = LoggerFactory.getLogger(WebaseClient.class);

    private final RestTemplate restTemplate;
    private final ChainProperties properties;
    private final WebaseErrorMapper errorMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebaseClient(RestTemplate chainRestTemplate,
                        ChainProperties properties,
                        WebaseErrorMapper errorMapper) {
        this.restTemplate = chainRestTemplate;
        this.properties = properties;
        this.errorMapper = errorMapper;
    }

    /** 相对上下文路径的完整地址，如 /trans/handle → http://127.0.0.1:5002/WeBASE-Front/trans/handle */
    public String url(String path) {
        String base = properties.getWebase().getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String context = properties.getWebase().getContextPath();
        if (context == null || context.isBlank()) {
            context = "";
        } else {
            if (!context.startsWith("/")) {
                context = "/" + context;
            }
            while (context.endsWith("/")) {
                context = context.substring(0, context.length() - 1);
            }
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + context + suffix;
    }

    public JsonNode post(String path, Object body) {
        return exchange(HttpMethod.POST, url(path), body);
    }

    public JsonNode get(String path) {
        return exchange(HttpMethod.GET, url(path), null);
    }

    private JsonNode exchange(HttpMethod method, String url, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
            return parse(response.getBody());
        } catch (HttpStatusCodeException e) {
            throw errorMapper.fromHttpStatus(method.name(), url, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw errorMapper.unreachable(url, e);
        } catch (RestClientException e) {
            log.warn("WeBASE 调用异常：{} {} - {}", method, url, e.getMessage());
            throw errorMapper.unreachable(url, e);
        }
    }

    /** 响应体解析：JSON 数组/对象原样返回，裸字符串包装为文本节点 */
    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        String trimmed = body.trim();
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[' || first == '"') {
            try {
                return objectMapper.readTree(trimmed);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "WeBASE 返回内容无法解析：" + summarize(trimmed));
            }
        }
        return TextNode.valueOf(trimmed);
    }

    private static String summarize(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
