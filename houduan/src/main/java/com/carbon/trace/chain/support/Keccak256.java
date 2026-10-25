package com.carbon.trace.chain.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Keccak-256（以太坊/Solidity 使用的哈希，不是 NIST SHA3-256）。
 *
 * 仅用于本地计算 ABI 函数选择器与事件 topic：
 * WeBASE 3.x 的事件订阅式查询会按等待超时返回「部分结果」，不能作为可靠的事件检索手段，
 * 因此交易归属改为直接解析区块交易的 input（见 WebaseChainGateway），需要选择器做本地判定。
 *
 * 实现约束：
 *  - 摘要为 256 位、状态宽度 1600 位、吸收率 136 字节（1088 位），与以太坊一致；
 *  - 填充字节为 0x01（Keccak 原版），不是 SHA-3 的 0x06，改错会导致选择器全部不匹配；
 *  - 本类无状态、线程安全，但每次调用都会新建状态数组，不宜在热路径中高频调用。
 */
public final class Keccak256 {

    /** 吸收率（字节）：1600 位状态减去 2×256 位输出容量后除以 8 */
    private static final int RATE = 136;

    /** 24 轮 ι 步使用的轮常数；顺序与 Keccak-f[1600] 规范一致，不可重排 */
    private static final long[] ROUND_CONSTANTS = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    /** 旋转位数，下标为 x * 5 + y */
    private static final int[] ROTATION = {
            0, 36, 3, 41, 18,
            1, 44, 10, 45, 2,
            62, 6, 43, 15, 61,
            28, 55, 25, 21, 56,
            27, 20, 39, 8, 14
    };

    /**
     * 私有构造器：本类只提供静态哈希方法，每次调用自行新建状态数组，禁止实例化。
     */
    private Keccak256() {
    }

    /**
     * 函数选择器：keccak256(签名) 的前 4 字节（8 位十六进制，不含 0x）。
     *
     * 签名必须使用规范形式（如 {@code certify(string,string,...)}），不能带空格或参数名，
     * 否则计算结果与链上交易 input 的前 4 字节不一致。
     *
     * @param signature 函数规范签名，如 {@code transfer(address,uint256)}
     * @return 8 位小写十六进制选择器
     */
    public static String selector(String signature) {
        return hashHex(signature).substring(0, 8);
    }

    /**
     * 对 UTF-8 字符串计算 Keccak-256。
     *
     * @param input 待哈希字符串，不能为 null
     * @return 64 位小写十六进制摘要
     */
    public static String hashHex(String input) {
        return hashHex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对字节数组计算 Keccak-256（海绵构造：先按 RATE 分块吸收再挤压出 32 字节摘要）。
     *
     * @param input 原始字节，不能为 null（空数组合法）
     * @return 64 位小写十六进制摘要
     */
    public static String hashHex(byte[] input) {
        // 海绵吸收前的填充：补齐到 RATE 的整数倍；末字节补 0x80 是 Keccak 域分隔符，
        // 紧邻输入末尾补 0x01 与 SHA-3 的 0x06 相对，用来区分两种填充规格。
        int padded = ((input.length + 1 + RATE - 1) / RATE) * RATE;
        byte[] data = Arrays.copyOf(input, padded);
        data[input.length] ^= 0x01;
        data[padded - 1] ^= (byte) 0x80;

        // 逐块吸收：每 8 字节按小端序组装成一个 64 位 lane，异或进 5×5 状态数组后做 24 轮置换
        long[] state = new long[25];
        for (int offset = 0; offset < padded; offset += RATE) {
            for (int lane = 0; lane < RATE / 8; lane++) {
                long value = 0L;
                for (int b = 7; b >= 0; b--) {
                    value = (value << 8) | (data[offset + lane * 8 + b] & 0xFFL);
                }
                state[lane] ^= value;
            }
            permute(state);
        }

        // 挤压阶段：前 4 个 lane（32 字节）即为摘要，按小端序还原成字节
        byte[] digest = new byte[32];
        for (int i = 0; i < 4; i++) {
            for (int b = 0; b < 8; b++) {
                digest[i * 8 + b] = (byte) (state[i] >>> (8 * b));
            }
        }
        // 转为小写十六进制串供选择器/topic 比对
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Keccak-f[1600] 主置换：24 轮，每轮依次执行 θ/ρ/π/χ/ι 五步。
     *
     * 状态数组按 {@code a[x + 5*y]} 线性存放（x 为列、y 为行），与 ROTATION 的下标约定一致；
     * 该置换原地修改入参数组，调用方不应复用置换前的值。
     *
     * @param a 25 个 64 位 lane 组成的状态数组
     */
    private static void permute(long[] a) {
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            // θ：列奇偶扩散到整列
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) {
                    a[x + y] ^= d;
                }
            }
            // ρ（按 ROTATION 循环移位）与 π（按 (2x+3y) 换位）合并到同一临时数组一次完成
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], ROTATION[x * 5 + y]);
                }
            }
            // χ：非线性变换（仅当前 lane 与相邻两 lane 参与）
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y]);
                }
            }
            // ι：异或当轮常数打破对称性
            a[0] ^= ROUND_CONSTANTS[round];
        }
    }

    /**
     * 64 位循环左移。
     *
     * bits 先对 64 取模，0 位直接返回原值（Java 的移位运算本身会取模，但显式处理可避免
     * {@code 64 - 0 = 64} 这种非法移位语义带来的歧义）。
     *
     * @param value 待移位值
     * @param bits  左移位数，可为任意整数
     * @return 循环左移后的值
     */
    private static long rotl(long value, int bits) {
        int n = bits & 63;
        return n == 0 ? value : (value << n) | (value >>> (64 - n));
    }
}
