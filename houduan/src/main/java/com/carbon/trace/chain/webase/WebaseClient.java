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
 *
 * 职责边界：本类只做 HTTP 与「错误 → 业务异常」的粗粒度映射，不理解合约语义；
 * ABI 编解码、回执判定、交易定位都在 {@code WebaseChainGateway} 中完成。
 *
 * 约束与易错点：
 *  - 超时来自 {@code carbon.chain.webase.timeout-ms}，由 {@code chainRestTemplate} 提供；
 *  - 所有非 2xx、网络不可达、响应无法解析都会抛 {@link ApiException}，调用方不需再判空；
 *  - 解析前按首个非空白字符判断形状，避免把裸文本当 JSON 解析而报出难以定位的错误；
 *  - 异常消息中的响应体统一截断到 200 字符，防止把整段链上错误刷进日志/接口响应。
 */
@Component
public class WebaseClient {

    private static final Logger log = LoggerFactory.getLogger(WebaseClient.class);

    private final RestTemplate restTemplate;
    private final ChainProperties properties;
    private final WebaseErrorMapper errorMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 装配客户端。
     *
     * @param chainRestTemplate 链专用 RestTemplate（Bean 名固定为 {@code chainRestTemplate}，
     *                          按构造参数名注入，保证不会拿到其他业务用的 RestTemplate）
     * @param properties        链配置，读取 base-url / context-path 与超时
     * @param errorMapper       HTTP 状态与 revert 原因的错误映射器
     */
    public WebaseClient(RestTemplate chainRestTemplate,
                        ChainProperties properties,
                        WebaseErrorMapper errorMapper) {
        this.restTemplate = chainRestTemplate;
        this.properties = properties;
        this.errorMapper = errorMapper;
    }

    /**
     * 相对上下文路径的完整地址，如 /trans/handle → http://127.0.0.1:5002/WeBASE-Front/trans/handle。
     *
     * 对 baseUrl 与 contextPath 做两端斜杠归一化：baseUrl 去掉末尾斜杠，contextPath 补齐首斜杠、
     * 去掉末尾斜杠，因此配置里写不写斜杠都能得到同一地址，避免出现 {@code //} 或漏斜杠。
     *
     * @param path 相对路径（可带或不带首斜杠）
     * @return 可直接请求的绝对 URL
     */
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

    /**
     * POST 指定路径并解析响应（用于 /trans/handle、/trans/handleWithSign 等写/只读调用）。
     *
     * @param path 相对上下文路径
     * @param body 请求体（{@code TransHandleRequest}），序列化由 RestTemplate 完成
     * @return 解析后的 JSON 节点；空响应体返回 {@link NullNode}
     * @throws ApiException 非 2xx、网络不可达或响应无法解析时抛出
     */
    public JsonNode post(String path, Object body) {
        return exchange(HttpMethod.POST, url(path), body);
    }

    /**
     * GET 指定路径并解析响应（用于区块/交易回执/统计等查询）。
     *
     * @param path 相对上下文路径，通常已拼接 groupId 与查询参数
     * @return 解析后的 JSON 节点；空响应体返回 {@link NullNode}
     * @throws ApiException 非 2xx、网络不可达或响应无法解析时抛出
     */
    public JsonNode get(String path) {
        return exchange(HttpMethod.GET, url(path), null);
    }

    /**
     * 统一发起 HTTP 请求：2xx 解析为 JsonNode，其余按错误类型归类映射为业务异常或 503。
     *
     * 异常分支顺序不可调换：{@link ResourceAccessException} 是 {@link RestClientException} 的子类，
     * 必须先捕获，否则网络不可达会被当成「其他客户端异常」而丢失「链服务不可用」的语义。
     *
     * @param method HTTP 方法
     * @param url    已拼装完成的绝对地址
     * @param body   请求体，GET 时为 null
     * @return 解析后的响应节点
     * @throws ApiException 所有失败路径统一转换，不向调用方泄漏底层 HTTP 异常
     */
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

    /**
     * 响应体解析：JSON 数组/对象原样返回，裸字符串包装为文本节点。
     *
     * 只看首个非空白字符判定形状：首字符为花括号或方括号时走 JSON 解析，为双引号时也按 JSON 解析
     * （保留引号，交由上层 unquote 处理），其余一律视为裸文本。
     *
     * @param body 原始响应文本，可为 null/空白
     * @return 解析结果；空响应返回 {@link NullNode}
     * @throws ApiException 形似 JSON 但解析失败时抛出（502），消息中只保留截断后的片段
     */
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

    /**
     * 截断过长响应文本到 200 字符，避免把整段错误写进日志或异常消息。
     *
     * @param value 原始文本，调用方需保证非 null
     * @return 截断后的文本（不超过 200 字符 + 省略号）
     */
    private static String summarize(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
