package com.carbon.trace.controller;

import com.carbon.trace.service.BlockchainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 区块链接口：展示联盟链的区块、交易、统计与健康信息，数据取自 WeBASE 联盟链上的合约，
 * 离线桩模式下对应数据为空。
 *
 * 暴露接口：
 *   GET /api/blockchain         最近若干区块（含交易明细），limit 不传时取配置 carbon.chain.block-list-limit；
 *   GET /api/blockchain/stats   链上统计指标；
 *   GET /api/blockchain/health  链健康信息（模式、可用性、最新区块、合约地址），供运维排障。
 * 角色约定：接口均需登录（经 AuthInterceptor 鉴权）。
 */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

    private final BlockchainService blockchainService;

    public BlockchainController(BlockchainService blockchainService) {
        this.blockchainService = blockchainService;
    }

    /** 最近若干区块（含交易明细）；limit 不传或为 0 时取配置 carbon.chain.block-list-limit */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int limit) {
        return blockchainService.listBlocks(limit);
    }

    /** 链上统计指标（区块数、交易数等汇总数据） */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return blockchainService.stats();
    }

    /** 链健康信息：模式、可用性、最新区块、合约地址，供运维与排障 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return blockchainService.health();
    }
}
