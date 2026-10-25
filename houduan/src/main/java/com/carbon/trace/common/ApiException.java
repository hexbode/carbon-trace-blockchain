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
 *
 * 常用状态码约定（全局统一）：
 *   400 参数缺失/格式非法/业务前置校验失败（默认值）；
 *   401 未登录或令牌失效；
 *   403 角色不足或企业归属不符；
 *   404 业务对象不存在；
 *   409 链上状态冲突（如防伪码重复、证书已撤销）；
 *   502/503 链层调用失败或 WeBASE-Front 不可达（由链层映射后以本异常抛出）。
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
