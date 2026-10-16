package com.carbon.trace.chain.webase;

import com.carbon.trace.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WeBASE / 合约错误 → 业务异常与中文提示的映射。
 *
 * 合约 revert 原因串（见 contracts/*.sol 的 require 第二参数）在这里翻译为面向用户的中文提示。
 */
@Component
public class WebaseErrorMapper {

    private record RevertSpec(HttpStatus status, String message) {
    }

    /** 合约 revert 原因 → （HTTP 状态, 中文提示） */
    private static final Map<String, RevertSpec> REVERT_MAP = new LinkedHashMap<>();

    static {
        REVERT_MAP.put("NOT_WRITER", new RevertSpec(HttpStatus.FORBIDDEN,
                "当前链账户无合约写入权限，请确认配置的账户已被授权为 writer"));
        REVERT_MAP.put("NOT_OWNER", new RevertSpec(HttpStatus.FORBIDDEN, "该操作需要合约 owner 权限"));
        REVERT_MAP.put("ZERO_ADDRESS", new RevertSpec(HttpStatus.BAD_REQUEST, "链上账户地址不能为空"));
        REVERT_MAP.put("CERT_ID_EMPTY", new RevertSpec(HttpStatus.BAD_REQUEST, "认证编号不能为空"));
        REVERT_MAP.put("FINGERPRINT_EMPTY", new RevertSpec(HttpStatus.BAD_REQUEST, "电子防伪码不能为空"));
        REVERT_MAP.put("FINGERPRINT_DUPLICATE", new RevertSpec(HttpStatus.CONFLICT, "该电子防伪码已被其他证书占用"));
        REVERT_MAP.put("CERT_REVOKED", new RevertSpec(HttpStatus.CONFLICT, "该证书已被监管机构撤销，不可重复认证"));
        REVERT_MAP.put("CERT_INVALIDATED", new RevertSpec(HttpStatus.CONFLICT, "该存证已作废，不可执行该操作"));
        REVERT_MAP.put("CERT_NOT_FOUND", new RevertSpec(HttpStatus.NOT_FOUND, "链上存证不存在"));
        REVERT_MAP.put("BAD_THRESHOLD", new RevertSpec(HttpStatus.BAD_REQUEST,
                "环比阈值非法（需大于 0 且不超过 10000 基点）"));
        REVERT_MAP.put("BAD_MONTHS", new RevertSpec(HttpStatus.BAD_REQUEST, "迟报月数非法（不超过 24）"));
    }

    /** 网络不可达 / 超时 */
    public ApiException unreachable(String url, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "联盟链服务不可用，请检查 WeBASE-Front 是否已启动以及 carbon.chain.webase.base-url 配置是否正确");
    }

    /** HTTP 非 2xx */
    public ApiException fromHttpStatus(String method, String url, HttpStatusCode status, String body) {
        ApiException revert = revertOf(body);
        if (revert != null) {
            return revert;
        }
        String detail = body == null || body.isBlank() ? "" : "：" + summarize(body);
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "联盟链调用失败（" + method + " " + status.value() + "）" + detail);
    }

    /** 写交易回执失败（status != 0x0 或无 statusOK） */
    public ApiException fromReceipt(String message, String output) {
        ApiException revert = revertOf(message);
        if (revert == null) {
            revert = revertOf(output);
        }
        if (revert != null) {
            return revert;
        }
        String detail = message == null || message.isBlank() ? String.valueOf(output) : message;
        return new ApiException(HttpStatus.BAD_GATEWAY, "链上交易执行失败：" + summarize(detail));
    }

    /** 从任意文本中识别合约 revert 原因 */
    public ApiException revertOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Map.Entry<String, RevertSpec> entry : REVERT_MAP.entrySet()) {
            if (text.contains(entry.getKey())) {
                RevertSpec spec = entry.getValue();
                return new ApiException(spec.status(), spec.message());
            }
        }
        return null;
    }

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
