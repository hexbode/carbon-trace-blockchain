package com.carbon.trace.chain.config;

import com.carbon.trace.chain.ChainMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链配置（carbon.chain.*）。
 *
 * 注意：真实环境请通过环境变量或 application-local.yml（不入库）提供地址与账户，
 * 仓库内只保留占位符；任何配置都不得出现私钥（Sign 模式后端只持有 signUserId）。
 *
 * 配置分层：
 *  - {@link Webase}：WeBASE-Front 地址、群组、签名账户与超时；
 *  - {@link Contracts}：两个合约的名称、地址与 ABI 位置（ABI 随仓库提供）；
 *  - {@link Compliance} / {@link Migration}：合规回退与历史迁移开关。
 *
 * mode 为字符串而非枚举，是为了让未知值能安全回退 STUB（见 {@link #chainMode()}），
 * 避免配置写错时应用直接启动失败。
 */
@Data
@ConfigurationProperties(prefix = "carbon.chain")
public class ChainProperties {

    /** 运行模式：webase（真实联盟链）| stub（离线开发桩） */
    private String mode = "stub";

    private Webase webase = new Webase();

    private Contracts contracts = new Contracts();

    /** 区块浏览每页最多返回的区块数 */
    private int blockListLimit = 20;

    /** 链上对账（ChainIntegrityChecker）定期间隔 */
    private long integrityIntervalMs = 300000;

    private Compliance compliance = new Compliance();

    private Migration migration = new Migration();

    /**
     * mode 字符串 → 枚举（未知值安全回退 STUB）。
     *
     * @return 解析后的 {@link ChainMode}，不会返回 null
     */
    public ChainMode chainMode() {
        return ChainMode.from(mode);
    }

    /**
     * 是否处于真实联盟链模式（webase）。
     *
     * 仅供需要在装配期之外做分支的组件使用（如对账、迁移）；模式判断应统一走这里，
     * 不要直接比较 {@link #getMode()}，以免大小写差异造成误判。
     *
     * @return true 表示解析结果为 {@link ChainMode#WEBASE}
     */
    public boolean isWebaseMode() {
        return chainMode() == ChainMode.WEBASE;
    }

    /**
     * WeBASE-Front 连接配置。
     *
     * groupId 在不同版本形态不同（2.x 为数字、3.x 为 group0），因此按字符串保存并原样下发。
     * 签名方式二选一：signUserId 非空时优先走 WeBASE-Sign 托管；否则使用 userAddress 的本地私钥用户。
     */
    @Data
    public static class Webase {

        /** WeBASE-Front 地址 */
        private String baseUrl = "http://127.0.0.1:5002";

        /** WeBASE-Front 上下文路径 */
        private String contextPath = "/WeBASE-Front";

        /**
         * 群组标识。
         *
         * WeBASE 1.5.x（FISCO BCOS 2.x）为数字，如 "1"；
         * WeBASE 3.x（FISCO BCOS 3.x）为 "group0"。两者都按字符串下发，避免版本差异。
         */
        private String groupId = "group0";

        /** WeBASE-Sign 托管用户（生产推荐，私钥不出 Sign 服务） */
        private String signUserId = "";

        /** WeBASE-Front 本地私钥用户地址（本机开发使用） */
        private String userAddress = "";

        /** HTTP 超时（毫秒） */
        private int timeoutMs = 15000;
    }

    /** 两个业务合约的配置；地址为空会在 WebaseChainGateway 启动自检时快速失败 */
    @Data
    public static class Contracts {

        private Contract registry = new Contract("CarbonCertRegistry", "", "classpath:chain/CarbonCertRegistry.abi.json");

        private Contract compliance = new Contract("CarbonCompliance", "", "classpath:chain/CarbonCompliance.abi.json");
    }

    /**
     * 单个合约的定位信息。
     *
     * name 必须与部署时登记的合约名一致（WeBASE 用它匹配合约记录与 ABI），
     * address 是部署产物而非固定常量，abiLocation 支持 classpath: 与 file: 两种前缀。
     */
    @Data
    public static class Contract {

        /** 合约名（需与部署时一致，WeBASE 用它定位 ABI 与合约记录） */
        private String name;

        /** 合约地址（部署产物，按环境配置） */
        private String address;

        /** ABI 文件位置（classpath 或 file 前缀） */
        private String abiLocation;

        /**
         * 构造合约配置。
         *
         * @param name        合约名（需与部署记录一致）
         * @param address     合约地址，可先留空，由部署脚本/环境变量注入
         * @param abiLocation ABI 资源位置
         */
        public Contract(String name, String address, String abiLocation) {
            this.name = name;
            this.address = address;
            this.abiLocation = abiLocation;
        }
    }

    /** 合规判定配置 */
    @Data
    public static class Compliance {

        /** 链不可用时是否回退到本地 Java 规则（保证填报流程不中断） */
        private boolean fallbackToJava = true;
    }

    /**
     * 历史数据迁移配置。
     *
     * 一次性开关默认关闭：开启后每次启动都会全量重放，重复认证会被合约拒绝，
     * 因此应在首次迁移完成后立即改回 false。
     */
    @Data
    public static class Migration {

        /** 历史数据上链一次性开关 */
        private boolean enabled = false;

        /** 旧自实现链数据文件（作为历史防伪码的补充来源） */
        private String legacyFile = "./data/blockchain.json";
    }
}
