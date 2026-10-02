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
 * 全局异常处理器：统一产出 {"message": "错误描述"}
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        // 未知异常仅返回通用文案，避免泄露内部实现细节；堆栈必须记录到日志便于排查
        log.error("unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务繁忙，请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
