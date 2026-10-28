// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;
pragma experimental ABIEncoderV2;

/**
 * @title 碳链通 · 认证存证数据合约
 * @notice 只负责保存认证存证的状态，不包含任何业务规则；所有写入函数均受 onlyLogic 限制，
 *         只有当前授权的逻辑合约（CertRegistryLogicV*）可以修改数据。
 *
 * @dev 逻辑与数据分离（FISCO BCOS 推荐的合约升级方案）：
 *  - 数据合约地址稳定，历史存证永久保留；
 *  - 升级时部署新的逻辑合约，由 owner 调用 updateLogic(newLogic) 完成切换，
 *    旧逻辑立即失权，无需迁移任何历史数据；
 *  - 与代理模式相比，不依赖 delegatecall 的存储布局兼容性，适合以追加写为主的存证场景。
 *
 * 存储结构一旦上线不可变更：本合约的任何字段增删都必须通过「新增数据合约 + 迁移」完成，
 * 因此这里的字段顺序与类型是全系统的持久化契约，调整前需评估链上已有数据。
 *
 * 状态常量与 Certificate 结构语义见原 CarbonCertRegistry 注释，此处保持一致。
 */
contract CertRegistryData {

    /// @notice 证书状态取值：0=未登记，1=有效，2=监管撤销（终局），3=业务记录已删除而作废
    uint8 public constant STATUS_NONE    = 0;
    uint8 public constant STATUS_VALID   = 1;
    uint8 public constant STATUS_REVOKED = 2;
    uint8 public constant STATUS_INVALID = 3;

    /**
     * @notice 单条认证存证记录。
     * @dev 字段顺序与类型即链上存储契约，逻辑合约按同名结构解析，不可随意调整。
     *      amountScaled 为排放量 ×100（2 位小数）；complianceFlags 为合规判定结果位掩码低 16 位。
     */
    struct Certificate {
        string  certId;
        string  enterpriseId;
        string  enterpriseName;
        string  period;
        uint256 amountScaled;
        string  fingerprint;
        string  auditor;
        string  reason;
        uint16  complianceFlags;
        uint64  createdBlock;
        uint64  createdAt;
        uint8   status;
        bool    exists;
    }

    /// @notice 数据合约管理员；部署时为部署账户，可调用 updateLogic 与（经逻辑合约）移交管理权
    address public owner;
    /// @notice 当前授权的逻辑合约地址；只有该地址可以调用本合约的所有写入函数
    address public logic;
    /// @notice 写入白名单快照；业务写权限由逻辑合约读取本映射后判定
    mapping(address => bool) public writers;

    /// @notice 历史累计认证数（只增不减）
    uint256 public totalCount;
    /// @notice 当前状态为 VALID 的数量
    uint256 public validCount;
    /// @notice 当前状态为 REVOKED 的数量
    uint256 public revokedCount;
    /// @notice 当前状态为 INVALID 的数量
    uint256 public invalidCount;

    /// @dev 三种索引互相独立，键均为原始字节的 keccak256，不做大小写归一化
    mapping(bytes32 => Certificate) private _certs;               // keccak(certId) -> 证书
    mapping(bytes32 => bytes32)     private _certIdByFingerprint; // keccak(fingerprint) -> keccak(certId)
    mapping(uint256 => string[])    private _certIdsInBlock;      // 区块高度 -> 本区块新增的认证编号

    /// @notice 逻辑合约切换成功时触发；升级审计的关键事件
    /// @param previousLogic 原逻辑合约地址（首次部署时为 address(0)）
    /// @param newLogic 新逻辑合约地址
    event LogicChanged(address indexed previousLogic, address indexed newLogic);

    /// @notice 仅数据合约管理员可调用，否则 revert("NOT_OWNER")
    modifier onlyOwner() {
        require(msg.sender == owner, "NOT_OWNER");
        _;
    }

    /// @notice 仅当前授权的逻辑合约可调用，否则 revert("NOT_LOGIC")
    modifier onlyLogic() {
        require(msg.sender == logic, "NOT_LOGIC");
        _;
    }

    /**
     * @notice 部署数据合约：部署账户成为 owner 与首个 writer。
     * @dev logic 初始为 address(0)，必须在部署逻辑合约后由 owner 调用 updateLogic 激活；
     *      在此之前所有写入函数都会被 onlyLogic 拒绝，符合「先建数据、再挂逻辑」的安全顺序。
     * @param initialWriters 初始写入方地址（业务后端账户、部署账户等），零地址会被跳过
     */
    constructor(address[] memory initialWriters) {
        owner = msg.sender;
        writers[msg.sender] = true;
        for (uint256 i = 0; i < initialWriters.length; i++) {
            if (initialWriters[i] != address(0)) {
                writers[initialWriters[i]] = true;
            }
        }
    }

    // ---------------- 治理 ----------------

    /**
     * @notice 切换逻辑合约（合约升级的唯一链上治理动作）。
     * @dev 权限：onlyOwner。切换后旧逻辑立即失权，历史数据与计数器保持不变。
     *      新逻辑必须由同一数据合约地址构造，否则读写会落到其他存储。
     * @param newLogic 新逻辑合约地址，不可为 address(0)
     */
    function updateLogic(address newLogic) external onlyOwner {
        require(newLogic != address(0), "ZERO_ADDRESS");
        address previous = logic;
        logic = newLogic;
        emit LogicChanged(previous, newLogic);
    }

    /**
     * @notice 移交数据合约管理权（业务上等同于移交整个存证合约的治理权）。
     * @dev 权限：onlyLogic（由逻辑合约校验调用者是 owner 后转发），新 owner 同时加入 writer 白名单。
     * @param newOwner 新管理员地址，不可为 address(0)
     */
    function transferOwner(address newOwner) external onlyLogic {
        require(newOwner != address(0), "ZERO_ADDRESS");
        owner = newOwner;
        writers[newOwner] = true;
    }

    /**
     * @notice 维护写入白名单。
     * @dev 权限：onlyLogic（由逻辑合约校验调用者是 owner 后转发）。
     * @param account 目标账户地址，不可为 address(0)
     * @param allowed true=加入白名单，false=移出白名单
     */
    function setWriter(address account, bool allowed) external onlyLogic {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = allowed;
    }

    // ---------------- 读取 ----------------

    /**
     * @notice 按认证编号哈希读取完整证书结构（供逻辑合约使用）。
     * @param certIdHash keccak256(bytes(certId))
     * @return 证书结构；未登记时 exists=false，其余字段为空/0
     */
    function certificateOf(bytes32 certIdHash) external view returns (Certificate memory) {
        return _certs[certIdHash];
    }

    /**
     * @notice 查询防伪码绑定的认证编号哈希。
     * @param fpHash keccak256(bytes(fingerprint))
     * @return 绑定的 keccak256(bytes(certId))；未绑定返回 bytes32(0)
     */
    function certIdHashOfFingerprint(bytes32 fpHash) external view returns (bytes32) {
        return _certIdByFingerprint[fpHash];
    }

    /**
     * @notice 返回某区块内「首次登记」的认证编号列表（存储拷贝，调用方修改不影响链上数据）。
     * @param blockNumber 区块高度
     * @return 该区块新增的 certId 数组（按登记顺序）
     */
    function certIdsInBlock(uint256 blockNumber) external view returns (string[] memory) {
        string[] storage ids = _certIdsInBlock[blockNumber];
        string[] memory out = new string[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = ids[i];
        }
        return out;
    }

    /**
     * @notice 四项计数快照。
     * @dev 恒有 total == valid + revoked + invalid；total 只增不减。
     */
    function counts() external view returns (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) {
        return (totalCount, validCount, revokedCount, invalidCount);
    }

    // ---------------- 写入（仅逻辑合约） ----------------

    /**
     * @notice 首次登记一条认证存证。
     * @dev 权限：onlyLogic。业务规则（防伪码唯一、是否已存在、状态是否可写）由逻辑合约先行校验，
     *      本函数只负责落库：写入内容、初始化 VALID、记录首次区块与时间、累计计数、追加区块索引。
     * @param certIdHash keccak256(bytes(certId))
     * @param certId 认证编号
     * @param enterpriseId 企业编号
     * @param enterpriseName 企业名称
     * @param period 报告期 YYYY-MM
     * @param amountScaled 排放量 ×100
     * @param fingerprint 电子防伪码原文
     * @param auditor 审核人
     * @param complianceFlags 合规判定位掩码
     * @param fpHash keccak256(bytes(fingerprint))
     */
    function writeNewCertificate(
        bytes32 certIdHash,
        string calldata certId,
        string calldata enterpriseId,
        string calldata enterpriseName,
        string calldata period,
        uint256 amountScaled,
        string calldata fingerprint,
        string calldata auditor,
        uint16 complianceFlags,
        bytes32 fpHash
    ) external onlyLogic {
        Certificate storage c = _certs[certIdHash];
        require(!c.exists, "CERT_EXISTS");

        c.certId = certId;
        c.enterpriseId = enterpriseId;
        c.enterpriseName = enterpriseName;
        c.period = period;
        c.amountScaled = amountScaled;
        c.fingerprint = fingerprint;
        c.auditor = auditor;
        c.complianceFlags = complianceFlags;
        c.reason = "";
        c.createdBlock = uint64(block.number);
        c.createdAt = uint64(block.timestamp);
        c.status = STATUS_VALID;
        c.exists = true;

        _certIdByFingerprint[fpHash] = certIdHash;
        _certIdsInBlock[block.number].push(certId);
        totalCount++;
        validCount++;
    }

    /**
     * @notice 覆盖已登记证书的内容字段（不含状态、计数与首次登记信息）。
     * @dev 权限：onlyLogic。reason 会被清空，防伪码重新绑定到本编号；
     *      旧防伪码不做解绑（只追加语义，历史码仍可反查到本编号）。
     */
    function writeCertificateContent(
        bytes32 certIdHash,
        string calldata enterpriseId,
        string calldata enterpriseName,
        string calldata period,
        uint256 amountScaled,
        string calldata fingerprint,
        string calldata auditor,
        uint16 complianceFlags,
        bytes32 fpHash
    ) external onlyLogic {
        Certificate storage c = _certs[certIdHash];
        require(c.exists, "CERT_NOT_FOUND");

        c.enterpriseId = enterpriseId;
        c.enterpriseName = enterpriseName;
        c.period = period;
        c.amountScaled = amountScaled;
        c.fingerprint = fingerprint;
        c.auditor = auditor;
        c.complianceFlags = complianceFlags;
        c.reason = "";
        _certIdByFingerprint[fpHash] = certIdHash;
    }

    /**
     * @notice 更新证书状态与原因。
     * @dev 权限：onlyLogic。状态迁移合法性与计数增减由逻辑合约负责，本函数只落库。
     * @param certIdHash keccak256(bytes(certId))
     * @param status 新状态（1/2/3）
     * @param reason 撤销/作废原因；恢复有效时传空串
     */
    function setCertificateStatus(bytes32 certIdHash, uint8 status, string calldata reason) external onlyLogic {
        Certificate storage c = _certs[certIdHash];
        require(c.exists, "CERT_NOT_FOUND");
        c.status = status;
        c.reason = reason;
    }

    /**
     * @notice 覆盖四项计数。
     * @dev 权限：onlyLogic。逻辑合约基于 counts() 快照计算后写入，保证四种状态转移一致。
     */
    function setCounts(uint256 total, uint256 valid, uint256 revoked, uint256 invalid) external onlyLogic {
        totalCount = total;
        validCount = valid;
        revokedCount = revoked;
        invalidCount = invalid;
    }
}
