package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 写交易的回执（/trans/handle 对非 constant 方法的返回）。
 *
 * 注意：blockNumber 可能是十进制字符串也可能是 0x 十六进制字符串，status 为 "0x0" 表示成功。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransHandleReceipt {

    @JsonProperty("transactionHash")
    private String transactionHash;

    @JsonProperty("blockNumber")
    private String blockNumber;

    /** 状态码："0x0" 表示成功 */
    @JsonProperty("status")
    private String status;

    @JsonProperty("statusOK")
    private Boolean statusOK;

    @JsonProperty("message")
    private String message;

    @JsonProperty("output")
    private String output;

    @JsonProperty("gasUsed")
    private String gasUsed;

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

    public long blockNumberLong() {
        return parseNumber(blockNumber);
    }

    /** 兼容十进制与 0x 十六进制字符串 */
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
