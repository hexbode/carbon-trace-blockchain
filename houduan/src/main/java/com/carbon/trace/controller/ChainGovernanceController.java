package com.carbon.trace.controller;

import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.chain.model.ChainContractKind;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链上合约治理接口：查看 owner / writer 状态，并增删写入白名单。
 *
 * 权限边界：
 *  - 查询：regulator / admin（便于监管端核对链上写入权限）；
 *  - 增删 writer：仅 admin（治理动作会真实写链，失败会返回链上 revert 原因）。
 *
 * 说明：writer 白名单在合约中是 mapping，没有枚举接口；页面只能按地址查询，
 * 不能列出全部 writer。存证类型的 owner/writer 读自数据合约，写操作走逻辑合约。
 */
@RestController
@RequestMapping("/api/chain-governance")
public class ChainGovernanceController {

    private final ChainGateway chainGateway;
    private final ChainProperties properties;

    public ChainGovernanceController(ChainGateway chainGateway, ChainProperties properties) {
        this.chainGateway = chainGateway;
        this.properties = properties;
    }

    /**
     * 治理总览：运行模式、逻辑版本、签名账户，以及三类合约的地址 / owner / 签名账户写入权限。
     *
     * @return 治理状态；单个合约读取失败时该合约带 error 字段，不影响整体返回
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        requireManagerRole();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", chainGateway.mode());
        result.put("logicVersion", chainGateway.logicVersion());
        result.put("signerAddress", signerAddress());
        result.put("signerLabel", signerLabel());

        List<Map<String, Object>> contracts = new ArrayList<>();
        for (ChainContractKind kind : ChainContractKind.values()) {
            contracts.add(contractStatus(kind));
        }
        result.put("contracts", contracts);
        return result;
    }

    /**
     * 查询任意地址在指定合约上的写入权限。
     *
     * @param kind    合约类型：registry / compliance / cbam-anchor
     * @param account 账户地址
     * @return owner / writer 状态
     */
    @GetMapping("/writer")
    public Map<String, Object> writer(@RequestParam String kind, @RequestParam String account) {
        requireManagerRole();
        ChainContractKind contractKind = requireKind(kind);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", contractKind.getCode());
        result.put("account", account);
        result.put("owner", safeOwner(contractKind));
        result.put("writer", chainGateway.isWriter(contractKind, account));
        result.put("configured", StringUtils.hasText(addressOf(contractKind)));
        return result;
    }

    /**
     * 增删写入白名单（真实写链，需 owner 账户签名）。
     *
     * 请求体：{ kind, account, allowed }；allowed 缺省为 true。
     * 后端签名账户不是 owner 时，合约会 revert NOT_OWNER，接口透传链上错误。
     *
     * @return 操作结果（kind / account / allowed）
     */
    @PostMapping("/writers")
    public Map<String, Object> setWriter(@RequestBody Map<String, Object> body) {
        requireAdminRole();
        ChainContractKind kind = requireKind(str(body.get("kind")));
        String account = str(body.get("account"));
        boolean allowed = body.get("allowed") == null
                || Boolean.parseBoolean(String.valueOf(body.get("allowed")));
        chainGateway.setWriter(kind, account, allowed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind.getCode());
        result.put("account", account);
        result.put("allowed", allowed);
        result.put("message", allowed ? "已加入写入白名单" : "已移出写入白名单");
        return result;
    }

    // ==================== 内部工具 ====================

    /** 单个合约的治理状态；owner / writer 读取失败只在该合约下记录 error */
    private Map<String, Object> contractStatus(ChainContractKind kind) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind.getCode());
        item.put("label", kind.getLabel());
        String address = addressOf(kind);
        item.put("address", address);
        item.put("configured", StringUtils.hasText(address));
        if (kind == ChainContractKind.REGISTRY) {
            item.put("dataAddress", trim(properties.getContracts().getRegistryData().getAddress()));
        }
        try {
            item.put("owner", chainGateway.ownerOf(kind));
        } catch (RuntimeException e) {
            item.put("owner", null);
            item.put("error", e.getMessage());
        }
        String signer = signerAddress();
        if (StringUtils.hasText(signer) && StringUtils.hasText(address)) {
            try {
                item.put("signerIsWriter", chainGateway.isWriter(kind, signer));
            } catch (RuntimeException e) {
                item.put("signerIsWriter", null);
                item.put("error", e.getMessage());
            }
        } else {
            item.put("signerIsWriter", null);
        }
        return item;
    }

    /** 合约地址：存证返回逻辑合约地址（业务写入口），其余返回各自地址 */
    private String addressOf(ChainContractKind kind) {
        String address = switch (kind) {
            case REGISTRY -> properties.getContracts().getRegistry().getAddress();
            case COMPLIANCE -> properties.getContracts().getCompliance().getAddress();
            case CBAM_ANCHOR -> properties.getContracts().getCbamAnchor().getAddress();
        };
        return trim(address);
    }

    private String safeOwner(ChainContractKind kind) {
        try {
            return chainGateway.ownerOf(kind);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 本地私钥模式下的签名账户地址；Sign 托管模式无法在本地得知地址，返回 null */
    private String signerAddress() {
        String address = properties.getWebase().getUserAddress();
        return StringUtils.hasText(address) ? address.trim() : null;
    }

    private String signerLabel() {
        String signUserId = properties.getWebase().getSignUserId();
        if (StringUtils.hasText(signUserId)) {
            return "WeBASE-Sign: " + signUserId.trim();
        }
        String address = signerAddress();
        return address == null ? "未配置" : address;
    }

    private ChainContractKind requireKind(String code) {
        ChainContractKind kind = ChainContractKind.from(code);
        if (kind == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "合约类型只支持 registry / compliance / cbam-anchor");
        }
        return kind;
    }

    private void requireManagerRole() {
        LoginUser user = UserContext.get();
        String role = user == null ? null : user.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查看链上治理信息");
        }
    }

    private void requireAdminRole() {
        LoginUser user = UserContext.get();
        if (user == null || !"admin".equals(user.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅管理员可修改链上写入白名单");
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
