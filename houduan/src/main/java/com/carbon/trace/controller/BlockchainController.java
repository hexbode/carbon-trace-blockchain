package com.carbon.trace.controller;

import com.carbon.trace.service.BlockchainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 区块链接口
 */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

    private final BlockchainService blockchainService;

    public BlockchainController(BlockchainService blockchainService) {
        this.blockchainService = blockchainService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return blockchainService.listBlocks();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return blockchainService.stats();
    }
}
