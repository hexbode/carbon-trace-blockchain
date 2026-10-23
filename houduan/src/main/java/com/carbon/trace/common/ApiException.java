package com.carbon.trace.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器统一转换为 {"message": ...}。
 *
 * 职责：把 Controller / Service 层的业务校验失败（权限不足、参数非法、数据不存在、
 * 状态不一致等）以统一异常形态抛出，避免各层自行拼装响应体。
 * 暴露的接口：
 *   {@code ApiException(HttpStatus, String)} 指定 HTTP 状态码 + 错误文案构造；
 *   {@code ApiException(String)} 便捷构造，默认按 400 返回；
 *   {@code getStatus()} 返回对应 HTTP 状态码，供全局异常处理器读取。
 */
public class ApiException extends RuntimeException {

    /** 该异常映射的 HTTP 状态码，全局异常处理器据此设置响应状态 */
    private final HttpStatus status;

    /** 用指定状态码与错误文案构造业务异常 */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** 便捷构造：错误文案 message，HTTP 状态码默认 400（Bad Request） */
    public ApiException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    /** 返回本异常对应的 HTTP 状态码，供全局异常处理器统一写回响应 */
    public HttpStatus getStatus() {
        return status;
    }
}
