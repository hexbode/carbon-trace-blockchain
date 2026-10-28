// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;
pragma experimental ABIEncoderV2;

import "./CertRegistryLogicV1.sol";

/**
 * @title 碳链通 · 认证存证逻辑合约 V2
 * @notice 在 V1 全部业务能力之上新增「链上最高认证序号」视图：
 *         后端生成新编号时可先比较序号与链上最大值，高于最大值即无需再逐号查询链上占用，
 *         把每次新增排放记录的 2 秒级链读降为一次低频读取。
 *
 * @dev 升级方式：部署本合约（构造参数含初始最高序号）→ 数据合约 updateLogic(本合约)。
 *      - highestCertSeqValue 只增不减；certify 成功后按认证编号的序号更新；
 *      - 初始值由部署脚本传入「当前链上最高认证序号」，避免升级后把历史编号误判为空闲；
 *      - 若初始值偏小，后端只会多一些逐号检查（安全方向），不会误用历史编号；
 *        若初始值偏大，才可能跳过历史编号检查，因此部署时必须取真实链上最大值。
 */
contract CertRegistryLogicV2 is CertRegistryLogicV1 {

    /// @notice 链上已出现的最高认证序号（认证编号 "CR" 后的数字部分）；只增不减
    uint256 private highestCertSeqValue;

    /**
     * @notice 绑定同一个数据合约，并写入升级前的链上最高认证序号。
     *
     * @param dataAddress       已部署的 CertRegistryData 地址
     * @param initialHighestSeq 当前链上最高认证序号（如 CR000085 → 85）
     */
    constructor(address dataAddress, uint256 initialHighestSeq) CertRegistryLogicV1(dataAddress) {
        highestCertSeqValue = initialHighestSeq;
    }

    /**
     * @notice 当前逻辑版本号，V2 返回 2。
     */
    function logicVersion() external pure override returns (uint256) {
        return 2;
    }

    /**
     * @notice 链上已出现的最高认证序号。
     *
     * 后端用它判断「新编号是否可能已上链」：序号大于该值必然未上链，可跳过逐号链查询。
     * 返回 0 表示尚无任何认证或初始值未设置，调用方应回退到逐号检查。
     */
    function highestCertSeq() external view returns (uint256) {
        return highestCertSeqValue;
    }

    /**
     * @notice 认证写入：先复用 V1 的全部业务规则，再维护链上最高序号。
     *
     * @dev 使用 super.certify 复用防伪码唯一、状态迁移、事件与计数逻辑；
     *      V1 的 onlyWriter 修饰符在 super 调用中按原始调用者校验。
     * @return created true=首次登记，false=同编号更新
     */
    function certify(
        string memory certId,
        string memory enterpriseId,
        string memory enterpriseName,
        string memory period,
        uint256 amountScaled,
        string memory fingerprint,
        string memory auditor,
        uint256 complianceFlags
    ) public override returns (bool created) {
        created = super.certify(certId, enterpriseId, enterpriseName, period,
                amountScaled, fingerprint, auditor, complianceFlags);
        uint256 seq = certSeqOf(certId);
        if (seq > highestCertSeqValue) {
            highestCertSeqValue = seq;
        }
    }

    /**
     * @notice 从认证编号 "CR" + 6 位数字中解析序号。
     *
     * 非法格式（长度不足、前缀不是 CR、含非数字字符）返回 0：宁可少更新最高序号，
     * 让后端多做一次逐号检查，也不把错误编号当作更高序号写入。
     *
     * @param certId 认证编号
     * @return 数字序号；格式非法返回 0
     */
    function certSeqOf(string memory certId) internal pure returns (uint256) {
        bytes memory raw = bytes(certId);
        if (raw.length <= 2 || raw[0] != bytes1(0x43) || raw[1] != bytes1(0x52)) {
            return 0;
        }
        uint256 value = 0;
        for (uint256 i = 2; i < raw.length; i++) {
            uint8 digit = uint8(raw[i]);
            if (digit < 48 || digit > 57) {
                return 0;
            }
            value = value * 10 + (digit - 48);
        }
        return value;
    }
}
