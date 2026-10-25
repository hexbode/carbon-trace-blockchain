package com.carbon.trace.chain.model;

import lombok.Data;

/**
 * 链统计：面向管理端「链上概览」的聚合视图。
 *
 * 字段由后端从多个来源拼装：区块/交易计数来自链网关，有效/撤销/作废数来自证书计数，
 * mode 与可用性用于提示当前是否真实联盟链。仅作展示，不落库。
 *
 * 口径提醒：{@code txCount} 是存证累计数（含作废），{@code blockCount} 是节点区块数，
 * 两者量级不同；stub 模式下 blockCount 为 0、chainAvailable 恒为 false，前端应据此提示。
 */
@Data
public class ChainStats {

    /** 区块总数 */
    private long blockCount;

    /** 链上累计认证存证数 */
    private long txCount;

    /** 当前有效存证数 */
    private long activeCount;

    /** 已撤销存证数 */
    private long revokedCount;

    /** 已作废存证数 */
    private long invalidCount;

    /** 运行模式：webase / stub */
    private String mode;

    /** 链是否可用 */
    private boolean chainAvailable;
}
