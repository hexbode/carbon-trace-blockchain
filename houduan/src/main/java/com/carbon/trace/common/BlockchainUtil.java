package com.carbon.trace.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 区块链工具类：
 * 1. 电子防伪码 —— 12 位随机字符串（大小写字母 + 数字）
 * 2. 交易哈希 / 区块哈希 / PoW 挖矿 / 默克尔根 —— SHA-256（UTF-8 编码，可正确处理 tCO₂ 下标 ₂）
 * 全部使用 JDK 自带实现，无第三方依赖
 */
public final class BlockchainUtil {

    private BlockchainUtil() {
    }

    /** 电子防伪码字符集：大小写字母 + 数字，共 62 个 */
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** 防伪码长度 */
    private static final int CODE_LENGTH = 12;

    /** 随机源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成电子防伪码：12 位随机字符串，由大小写字母与数字组成。
     * 同一条业务记录的排放记录、报告、认证记录、区块交易共用同一个防伪码。
     */
    public static String generateAntiFakeCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** SHA-256，返回 64 位小写十六进制（不带 0x 前缀） */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /**
     * 按 JS JSON.stringify 的规则输出数字：
     * 整数不带小数点，小数去掉末尾多余的 0
     */
    public static String formatNumber(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal bd) {
            BigDecimal stripped = bd.stripTrailingZeros();
            return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return value.toString();
        }
        double d = ((Number) value).doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 生成电子防伪码（不带 0x 前缀的 64 位十六进制）。
     * 序列化格式严格复现 JS：
     * {"enterpriseId":"ENT20240001","period":"2024-01","source":"S01","amount":347.43,"unit":"tCO₂","timestamp":1704067200000}
     */
    public static String generateDataFingerprint(Map<String, Object> data) {
        String json = "{"
                + "\"enterpriseId\":\"" + escape(str(data.get("enterpriseId"))) + "\","
                + "\"period\":\"" + escape(str(data.get("period"))) + "\","
                + "\"source\":\"" + escape(str(data.get("source"))) + "\","
                + "\"amount\":" + formatNumber(data.get("amount")) + ","
                + "\"unit\":\"" + escape(str(data.get("unit"))) + "\","
                + "\"timestamp\":" + formatNumber(data.get("timestamp"))
                + "}";
        return sha256(json);
    }

    /** 生成交易哈希：'0x' + sha256(json + timestamp + random) */
    public static String generateTxHash(Map<String, Object> payload) {
        long timestamp = System.currentTimeMillis();
        String random = UUID.randomUUID().toString().replace("-", "");
        String raw = toJson(payload) + timestamp + random;
        return "0x" + sha256(raw);
    }

    /** 生成区块哈希：'0x' + sha256(index|previousHash|timestamp|merkleRoot|nonce) */
    public static String generateBlockHash(int index, String previousHash, long timestamp, String merkleRoot, int nonce) {
        String raw = index + "|" + previousHash + "|" + timestamp + "|" + merkleRoot + "|" + nonce;
        return "0x" + sha256(raw);
    }

    /**
     * PoW 挖矿：难度 2（哈希前两位为 '0'），最多尝试 maxTries 次
     */
    public static MiningResult mineBlock(int index, String previousHash, long timestamp,
                                         String merkleRoot, int difficulty, int maxTries) {
        String prefix = "0".repeat(Math.max(0, difficulty));
        int nonce = 0;
        String hash = generateBlockHash(index, previousHash, timestamp, merkleRoot, nonce);
        while (!hash.startsWith(prefix) && nonce < maxTries) {
            nonce++;
            hash = generateBlockHash(index, previousHash, timestamp, merkleRoot, nonce);
        }
        return new MiningResult(hash, nonce);
    }

    /** 计算默克尔根（两两哈希向上归并，奇数个则复制末项） */
    public static String merkleRoot(List<String> txHashes) {
        if (txHashes == null || txHashes.isEmpty()) {
            return "0x" + sha256("");
        }
        List<String> level = new ArrayList<>(txHashes);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                String left = level.get(i);
                String right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                next.add(sha256(left + right));
            }
            level = next;
        }
        String root = level.get(0);
        // 仅 1 笔交易时，元素本身就是带 0x 前缀的交易哈希，避免输出 0x0x 重复前缀
        return root.startsWith("0x") ? root : "0x" + root;
    }

    /** 简易 JSON 序列化（JS JSON.stringify 风格，无空格） */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(str(entry.getKey()))).append("\":");
                writeJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            if (value instanceof BigDecimal bd) {
                sb.append(formatNumber(bd));
            } else {
                sb.append(value);
            }
        } else {
            sb.append('"').append(escape(str(value))).append('"');
        }
    }

    /** 挖矿结果 */
    public record MiningResult(String hash, int nonce) {
    }
}
