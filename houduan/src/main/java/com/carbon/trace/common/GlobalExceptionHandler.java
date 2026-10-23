package com.carbon.trace.common;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器：兜底捕获 Controller / Service 抛出的各类异常，统一产出 JSON 响应体
 * {"message": "错误描述"}，避免异常堆栈与内部实现细节泄漏到前端。
 *
 * 处理清单（按 Spring 异常解析规则命中）：
 *   ApiException                          业务异常，按其携带的状态码返回；
 *   NoResourceFoundException              静态资源不存在，按 404 而非 500 返回；
 *   MissingServletRequestParameterException 缺少必需参数，按 400 返回；
 *   HttpMessageNotReadableException        请求体缺失 / JSON 格式错误，按 400 返回；
 *   MaxUploadSizeExceededException         上传文件超限，按 400 返回；
 *   DuplicateKeyException                 唯一约束冲突，返回可读提示且不暴露表结构；
 *   DataAccessException                   其余数据库异常，返回通用校验失败提示并记录完整日志；
 *   Exception                             未知异常兜底，通用文案 + 记录堆栈日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：直接采用异常携带的 HTTP 状态码与消息，是业务校验失败统一出口 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return build(e.getStatus(), e.getMessage());
    }

    /** 静态资源（如上传的头像）不存在时按 404 返回，而非 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    /** 缺少必需的请求参数时返回 400，而非 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "缺少必需参数：" + e.getParameterName());
    }

    /** 请求体缺失或 JSON 格式有误时返回 400，而非 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "请求体格式有误或内容缺失");
    }

    /** 上传文件超过 multipart 大小限制时返回 400，而非 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return build(HttpStatus.BAD_REQUEST, "上传文件过大，图片大小不能超过 5MB");
    }

    /** 主键/唯一约束冲突：给出可读提示，不透露表结构 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("duplicate key: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "数据已存在，请勿重复提交");
    }

    /**
     * 其余数据库访问异常（约束违反、字段超长等）统一返回 400 与通用提示，
     * 严禁把 MyBatis 原始 message（含 SQL、表名、字段名）透出到前端。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException e) {
        log.error("data access error", e);
        return build(HttpStatus.BAD_REQUEST, "数据校验失败，请检查填写内容是否完整、格式是否正确");
    }

    /** 兜底处理任何未命中更具体分支的异常：仅返回通用文案，完整堆栈记入日志便于排查 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        // 未知异常仅返回通用文案，避免泄露内部实现细节；堆栈必须记录到日志便于排查
        log.error("unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务繁忙，请稍后重试");
    }

    /** 统一组装响应体 {"message": ...} 并按给定 HTTP 状态码返回 */
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
