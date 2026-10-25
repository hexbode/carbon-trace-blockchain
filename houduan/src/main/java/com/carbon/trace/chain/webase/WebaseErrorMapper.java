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
 *
 * 映射顺序即优先级：{@link #revertOf(String)} 使用「包含匹配」，因此表中顺序会影响结果。
 * 新增原因串时需避免与既有串构成前缀/子串关系（例如把 {@code CERT_REVOKED} 写成 {@code REVOKED}
 * 会把 {@code CERT_REVOKED} 抢先命中）。
 *
 * 未识别的 revert 一律降级为 502 + 截断后的原文，保证前端仍能看到可排查的信息。
 */
@Component
public class WebaseErrorMapper {

    /**
     * 一条 revert 原因的映射规则。
     *
     * @param status  映射后的 HTTP 状态
     * @param message 面向用户的中文提示
     */
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

    /**
     * 网络不可达 / 超时。
     *
     * 统一映射为 503，提示中带上排查方向（WeBASE-Front 是否启动、base-url 是否正确）；
     * 不把底层异常消息直接暴露给前端，仅保留 cause 供日志链追踪。
     *
     * @param url   请求地址（当前仅用于定位，不写入提示）
     * @param cause 原始网络异常
     * @return 503 业务异常
     */
    public ApiException unreachable(String url, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "联盟链服务不可用，请检查 WeBASE-Front 是否已启动以及 carbon.chain.webase.base-url 配置是否正确");
    }

    /**
     * HTTP 非 2xx。
     *
     * 先尝试从响应体中识别 revert 原因（WeBASE 常把合约错误放在 4xx/5xx 的 body 里），
     * 识别失败才降级为 502 + 「方法 + 状态码 + 截断响应体」。
     *
     * @param method HTTP 方法名
     * @param url    请求地址（不写入提示）
     * @param status HTTP 状态
     * @param body   响应体原文，可为 null/空白
     * @return 映射后的业务异常
     */
    public ApiException fromHttpStatus(String method, String url, HttpStatusCode status, String body) {
        ApiException revert = revertOf(body);
        if (revert != null) {
            return revert;
        }
        String detail = body == null || body.isBlank() ? "" : "：" + summarize(body);
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "联盟链调用失败（" + method + " " + status.value() + "）" + detail);
    }

    /**
     * 写交易回执失败（status != 0x0 或无 statusOK）。
     *
     * 依次在 message 与 output 中查找 revert 原因：不同 WeBASE 版本把原因放在不同字段，
     * 两处都未命中时以 message 为主、output 兜底，拼成可排查的 502 提示。
     *
     * @param message 回执 message，可为 null/空白
     * @param output  回执 output（通常是 revert 原因或编码后的返回值），可为 null
     * @return 映射后的业务异常
     */
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

    /**
     * 从任意文本中识别合约 revert 原因。
     *
     * 使用子串包含匹配（而非等值匹配），因为 WeBASE 返回的文本通常带前后缀；
     * 代价是可能误命中，因此原因串需保持足够特异。
     *
     * @param text 待识别文本，可为 null/空白
     * @return 命中的业务异常；未命中返回 null（调用方需判空）
     */
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

    /**
     * 截断过长文本到 200 字符，防止把整段 revert 原文写进异常消息。
     *
     * @param value 原始文本，可为 null
     * @return 截断后的文本，null 返回空串
     */
    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
