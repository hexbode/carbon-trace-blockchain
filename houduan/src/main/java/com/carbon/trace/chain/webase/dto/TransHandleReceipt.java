package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 写交易的回执（/trans/handle 对非 constant 方法的返回）。
 *
 * 注意：blockNumber 可能是十进制字符串也可能是 0x 十六进制字符串，status 为 "0x0" 表示成功。
 * 不同 WeBASE 版本对 status / statusOK 的填充不一致，因此成功判定必须同时兼容两者（见 {@link #successful()}）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransHandleReceipt {

    /** 交易哈希（写链成功后用于回填凭证与交易定位缓存） */
    @JsonProperty("transactionHash")
    private String transactionHash;

    /** 出块高度，字符串形式（十进制或 0x 十六进制），解析见 {@link #blockNumberLong()} */
    @JsonProperty("blockNumber")
    private String blockNumber;

    /** 状态码："0x0" 表示成功 */
    @JsonProperty("status")
    private String status;

    /** 布尔状态：false 明确表示失败；null 表示该版本未提供，需要回退看 status */
    @JsonProperty("statusOK")
    private Boolean statusOK;

    /** 失败原因（部分版本把 revert 原因放在这里） */
    @JsonProperty("message")
    private String message;

    /** 合约返回值或 revert 原文（失败时用于识别原因） */
    @JsonProperty("output")
    private String output;

    /** 本次交易消耗的 gas，仅作展示 */
    @JsonProperty("gasUsed")
    private String gasUsed;

    /**
     * 交易是否成功：statusOK=false 直接判失败；否则按 status 比对 0x0/0x00/0 判成功。
     *
     * 兼容逻辑：status 与 statusOK 都缺失时返回 true（部分版本的回执只有 transactionHash），
     * 此时真正的失败会由后续读链或对账暴露，而不是在这一步误判。
     *
     * @return true 表示交易已成功执行
     */
    public boolean successful() {
        if (Boolean.FALSE.equals(statusOK)) {
            return false;
        }
        if (status == null || status.isBlank()) {
            // 部分版本只返回 statusOK
            return !Boolean.FALSE.equals(statusOK);
        }
        String s = status.trim().toLowerCase();
        if (s.startsWith("0x")) {
            return s.equals("0x0") || s.equals("0x00");
        }
        return s.equals("0");
    }

    /**
     * 出块高度的数值形式；无法解析时返回 -1。
     *
     * -1 是「缺失」的哨兵值，调用方需据此决定是否回填 blockIndex，不能当作真实高度 0。
     *
     * @return 区块高度；缺失或格式非法时返回 -1
     */
    public long blockNumberLong() {
        return parseNumber(blockNumber);
    }

    /**
     * 兼容十进制与 0x 十六进制字符串的数值解析。
     *
     * WeBASE 2.x/3.x 对同一字段可能给出不同进制，这里统一处理；空值或非法格式返回 -1。
     *
     * @param value 待解析字符串，可为 null/空白
     * @return 解析出的 long 值；无法解析时返回 -1
     */
    public static long parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        String v = value.trim();
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Long.parseLong(v.substring(2), 16);
            }
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
