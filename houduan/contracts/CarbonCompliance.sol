// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;

/**
 * @title 碳链通 · 排放合规判定合约
 * @notice 在链上执行五类告警规则判定，返回「判定结果位掩码 + 相关数值」，不写业务数据，也不生成告警文案。
 * @dev 全部告警规则判定在合约内执行，合约只返回「结果位掩码 + 数值」，
 *      告警级别与中文文案仍由后端生成（保持 alerts 表结构与前端契约不变）。
 *
 * 规则与位掩码（flags）：
 *   bit0 年度配额超限      QUOTA_EXCEEDED
 *   bit1 环比波动超阈值    FLUCTUATION_EXCEEDED
 *   bit2 活动数据为 0      MISSING_ACTIVITY_DATA
 *   bit3 排放因子不符      FACTOR_MISMATCH
 *   bit4 迟报（补报）      LATE_REPORT
 *
 * 可治理参数（链上变更留痕）：环比阈值（基点）、迟报月数、企业年度配额。
 * 输入边界：本期/上期合计、年累计、报告期与当前月份由后端从业务库聚合后传入——
 * 链上无法自行聚合业务库数据，这是必要的输入边界。
 *
 * 金额口径（与 Java 侧 AmountCodec 一致）：排放量/配额/合计 ×100（2 位小数），
 * 排放因子 ×10000（4 位小数）；基点与 YYYYMM 为原始整数，不做缩放。
 * 舍入口径：金额由后端按 HALF_UP 定标后传入，链上只做整数比较；基点结果由 _deltaBp 统一对齐到 10bp。
 */
contract CarbonCompliance {

    /// @notice 单条规则的判定码：0=合规，1~5 依次对应五类规则（判定码不是位掩码）
    /// @dev 仅 checkCompliance / checkFluctuation / checkActivity / checkFactor / checkLateReport 使用；
    ///      checkAll 返回的是下方 FLAG_*，映射关系为 FLAG_X = 1 << (判定码 - 1)。
    uint8 public constant OK                    = 0;
    uint8 public constant QUOTA_EXCEEDED        = 1;
    uint8 public constant FLUCTUATION_EXCEEDED  = 2;
    uint8 public constant MISSING_ACTIVITY_DATA = 3;
    uint8 public constant FACTOR_MISMATCH       = 4;
    uint8 public constant LATE_REPORT           = 5;

    /// @notice checkAll 返回的位掩码：1=配额超限、2=环比超阈值、4=活动数据为 0、8=因子不符、16=迟报
    /// @dev 多条规则同时命中时按位或累加，后端必须按位解析而不是按数值大小比较。
    uint16 public constant FLAG_QUOTA       = 1;
    uint16 public constant FLAG_FLUCTUATION = 2;
    uint16 public constant FLAG_ACTIVITY    = 4;
    uint16 public constant FLAG_FACTOR      = 8;
    uint16 public constant FLAG_LATE        = 16;

    /**
     * @notice 百分比取整粒度：10bp = 0.1%，与 Java 侧 BigDecimal scale=1 HALF_UP 口径一致
     * @dev _deltaBp 的最终结果恒为 BP_STEP 的整数倍，避免链上/链下舍入差异造成阈值边界不一致。
     */
    uint256 public constant BP_STEP = 10;

    /// @notice 合约管理员，可增删 writer（本合约未提供管理员移交函数，owner 部署后不再变化）
    address public owner;
    /// @notice 写入白名单：owner 与 writer 可写治理参数与镜像数据，其余地址只能读
    mapping(address => bool) public writers;

    /**
     * @notice 环比波动阈值，基点：2000 = ±20.00%
     * @dev 判定条件为 |deltaBp| > 阈值（严格大于，恰好等于阈值视为合规）；可取值范围 1~10000。
     */
    uint256 public fluctuationThresholdBp = 2000;

    /**
     * @notice 迟报判定：报告期早于当前月份超过该月数即视为迟报
     * @dev 取值 0~24。为 0 时截止月即当前月，报告期须早于当前月才算迟报；当月及以后均合规。
     */
    uint256 public lateReportMonths = 1;

    /**
     * @dev 镜像表与参数表，键统一为 _key2(a, b) = keccak256(abi.encodePacked(a, "|", b))：
     *  - _annualQuota / _yearTotal：企业编号 | 年份（十进制字符串，经 _uintToStr 生成，无前导零）；
     *    两者键构造完全相同，但存放于不同 mapping：配额是治理参数，年累计是可刷新的镜像。
     *  - _periodTotal：企业编号 | 报告期字符串（原样参与哈希，如 "2024-01" 与 "202401" 是不同键）；
     *    该镜像仅作链上留痕/审计，checkAll 的环比判定使用入参 previousTotalScaled，不回读它。
     *  - _lastFactor：企业编号 | 排放源编码，保存最近一次使用的因子，作为下一次「因子不符」的基准。
     * 标度：配额/年累计/本期合计 ×100（2 位小数），因子 ×10000（4 位小数）。
     * 键不做大小写或空白归一化，调用方须保证 enterpriseId / sourceCode 与写入时逐字节一致。
     */
    mapping(bytes32 => uint256) private _annualQuota; // keccak(enterpriseId|year)       -> 配额 × 100
    mapping(bytes32 => uint256) private _yearTotal;   // keccak(enterpriseId|year)       -> 年累计 × 100
    mapping(bytes32 => uint256) private _periodTotal; // keccak(enterpriseId|period)     -> 本期合计 × 100
    mapping(bytes32 => uint256) private _lastFactor;  // keccak(enterpriseId|sourceCode) -> 最近使用因子 × 10000

    /// @notice 年度配额写入/覆盖时触发
    /// @param key keccak256(enterpriseId + "|" + 年份)
    /// @param enterpriseId 企业编号
    /// @param year 年份
    /// @param quotaScaled 配额 ×100
    event QuotaSet(bytes32 indexed key, string enterpriseId, uint256 year, uint256 quotaScaled);
    /// @notice 本期合计镜像写入时触发
    /// @param key keccak256(enterpriseId + "|" + period)
    /// @param enterpriseId 企业编号
    /// @param period 报告期字符串（原样写入）
    /// @param amountScaled 本期合计 ×100
    event PeriodTotalSet(bytes32 indexed key, string enterpriseId, string period, uint256 amountScaled);
    /// @notice 年累计镜像写入时触发
    /// @param key keccak256(enterpriseId + "|" + 年份)
    /// @param enterpriseId 企业编号
    /// @param year 年份
    /// @param amountScaled 年累计 ×100
    event YearTotalSet(bytes32 indexed key, string enterpriseId, uint256 year, uint256 amountScaled);
    /// @notice 最近使用因子被覆盖记录时触发
    /// @param key keccak256(enterpriseId + "|" + sourceCode)
    /// @param enterpriseId 企业编号
    /// @param sourceCode 排放源编码
    /// @param factorScaled 因子 ×10000
    event FactorRemembered(bytes32 indexed key, string enterpriseId, string sourceCode, uint256 factorScaled);
    /// @notice 环比阈值变更时触发，便于链上追溯治理动作
    /// @param oldBp 变更前阈值（基点）
    /// @param newBp 变更后阈值（基点）
    event FluctuationThresholdChanged(uint256 oldBp, uint256 newBp);
    /// @notice 迟报月数变更时触发
    /// @param oldMonths 变更前月数
    /// @param newMonths 变更后月数
    event LateReportMonthsChanged(uint256 oldMonths, uint256 newMonths);
    /// @notice writer 白名单变更；allowed=false 表示移除
    /// @param account 被变更的账户地址
    /// @param allowed 变更后的白名单状态
    event WriterChanged(address indexed account, bool allowed);
    /// @notice 管理员变更事件（预留：本合约未实现管理员移交，当前代码不会发出该事件）
    /// @param previousOwner 原管理员地址
    /// @param newOwner 新管理员地址
    event OwnerChanged(address indexed previousOwner, address indexed newOwner);

    /// @notice 仅合约管理员可调用，否则 revert("NOT_OWNER")
    modifier onlyOwner() {
        require(msg.sender == owner, "NOT_OWNER");
        _;
    }

    /// @notice 仅白名单 writer 或 owner 可调用，否则 revert("NOT_WRITER")；owner 天然具备写权限
    modifier onlyWriter() {
        require(writers[msg.sender] || msg.sender == owner, "NOT_WRITER");
        _;
    }

    /// @notice 部署时把部署账户设为 owner 并加入 writer 白名单；两参数保持合约默认值（2000bp、1 个月）
    constructor() {
        owner = msg.sender;
        writers[msg.sender] = true;
        emit WriterChanged(msg.sender, true);
    }

    // ---------------- 权限 ----------------

    /**
     * @notice 新增写入白名单账户。
     * @dev 权限：onlyOwner。重复授权幂等，状态不变但仍发事件；零地址被拒绝。
     * @param account 待授权的账户地址，不可为 address(0)
     */
    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = true;
        emit WriterChanged(account, true);
    }

    /**
     * @notice 移除写入白名单账户。
     * @dev 权限：onlyOwner。不校验地址是否存在，幂等；owner 的写权限不受影响（onlyWriter 始终放行 owner）。
     * @param account 待移除的账户地址
     */
    function removeWriter(address account) external onlyOwner {
        writers[account] = false;
        emit WriterChanged(account, false);
    }

    // ---------------- 可治理参数 ----------------

    /**
     * @notice 调整环比波动阈值（基点）。
     * @dev 权限：onlyWriter。约束 0 < bp <= 10000，否则 revert("BAD_THRESHOLD")；
     *      立即影响后续 checkFluctuation / checkAll 的判定（> 阈值才算超限），已生成的告警不受影响。
     * @param bp 新阈值，单位基点（1bp = 0.01%），如 2000 表示 ±20.00%
     */
    function setFluctuationThresholdBp(uint256 bp) external onlyWriter {
        require(bp > 0 && bp <= 10000, "BAD_THRESHOLD");
        uint256 old = fluctuationThresholdBp;
        fluctuationThresholdBp = bp;
        emit FluctuationThresholdChanged(old, bp);
    }

    /**
     * @notice 调整迟报判定允许的月数。
     * @dev 权限：onlyWriter。约束 months <= 24，否则 revert("BAD_MONTHS")；0 合法。
     *      立即影响后续 checkLateReport / checkAll：截止月 = 当前月往前推 months 个月，报告期早于截止月才算迟报。
     * @param months 允许的滞后月数（含 0）
     */
    function setLateReportMonths(uint256 months) external onlyWriter {
        require(months <= 24, "BAD_MONTHS");
        uint256 old = lateReportMonths;
        lateReportMonths = months;
        emit LateReportMonthsChanged(old, months);
    }

    // ---------------- 链上状态写入 ----------------

    /**
     * @notice 设置企业某年度的排放配额。
     * @dev 权限：onlyWriter。覆盖写（幂等）；写入 0 等价于「未配置配额」，会让配额规则被跳过，
     *      因此无法用 0 表达真实的零配额。键为 _key2(enterpriseId, _uintToStr(year))。
     * @param enterpriseId 企业编号
     * @param year 年份（十进制字符串化后参与键构造）
     * @param quotaScaled 配额 ×100（2 位小数）
     */
    function setAnnualQuota(string memory enterpriseId, uint256 year, uint256 quotaScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        _annualQuota[key] = quotaScaled;
        emit QuotaSet(key, enterpriseId, year, quotaScaled);
    }

    /**
     * @notice 查询企业某年度配额。
     * @dev 未配置返回 0，调用方须结合「0 表示未配置」的语义使用。
     * @param enterpriseId 企业编号
     * @param year 年份
     * @return 配额 ×100；未配置为 0
     */
    function annualQuotaOf(string memory enterpriseId, uint256 year) external view returns (uint256) {
        return _annualQuota[_key2(enterpriseId, _uintToStr(year))];
    }

    /**
     * @notice 写入企业某报告期的排放量合计镜像。
     * @dev 权限：onlyWriter。覆盖写（幂等，后端每次填报后同步）。
     *      该镜像当前不被 checkAll 回读（环比使用入参上期值），仅作链上留痕与审计；
     *      period 原样参与哈希，调用方须保持与查询时同一写法（"2024-01" 与 "202401" 视为不同报告期）。
     * @param enterpriseId 企业编号
     * @param period 报告期字符串（约定 YYYY-MM）
     * @param amountScaled 本期合计 ×100（2 位小数）
     */
    function setPeriodTotal(string memory enterpriseId, string memory period, uint256 amountScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, period);
        _periodTotal[key] = amountScaled;
        emit PeriodTotalSet(key, enterpriseId, period, amountScaled);
    }

    /**
     * @notice 查询企业某报告期的合计镜像。
     * @dev 与 setPeriodTotal 使用相同的键构造；未写入返回 0，且 period 写法须完全一致。
     * @param enterpriseId 企业编号
     * @param period 报告期字符串
     * @return 本期合计 ×100；未写入为 0
     */
    function periodTotalOf(string memory enterpriseId, string memory period) external view returns (uint256) {
        return _periodTotal[_key2(enterpriseId, period)];
    }

    /**
     * @notice 写入企业某年度的累计排放量镜像（配额判定用）。
     * @dev 权限：onlyWriter。覆盖写（幂等）；checkCompliance / checkAll 优先读取该镜像，
     *      镜像为 0（尚未同步）时才回退到链下传入的年累计值。
     * @param enterpriseId 企业编号
     * @param year 年份
     * @param amountScaled 年累计 ×100（2 位小数）
     */
    function setYearTotal(string memory enterpriseId, uint256 year, uint256 amountScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        _yearTotal[key] = amountScaled;
        emit YearTotalSet(key, enterpriseId, year, amountScaled);
    }

    /**
     * @notice 查询企业某年度累计镜像。
     * @dev 与 setYearTotal 使用相同的键构造；未同步返回 0，此时判定逻辑回退到链下传入值。
     * @param enterpriseId 企业编号
     * @param year 年份
     * @return 年累计 ×100；未同步为 0
     */
    function yearTotalOf(string memory enterpriseId, uint256 year) external view returns (uint256) {
        return _yearTotal[_key2(enterpriseId, _uintToStr(year))];
    }

    /**
     * @notice 记录某企业某排放源最近一次使用的排放因子，作为「因子不符」的比对基准。
     * @dev 权限：onlyWriter。覆盖写（幂等），本身不做任何判定；0 值与「无历史」无法区分，
     *      因此把因子写成 0 会让该键等价于未记录。键为 _key2(enterpriseId, sourceCode)。
     * @param enterpriseId 企业编号
     * @param sourceCode 排放源编码
     * @param factorScaled 因子 ×10000（4 位小数）
     */
    function rememberFactor(string memory enterpriseId, string memory sourceCode, uint256 factorScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, sourceCode);
        _lastFactor[key] = factorScaled;
        emit FactorRemembered(key, enterpriseId, sourceCode, factorScaled);
    }

    /**
     * @notice 查询某企业某排放源最近一次记录的因子。
     * @dev 返回 0 表示未记录（或记录值本身为 0），判定时按「无历史基准」处理。
     * @param enterpriseId 企业编号
     * @param sourceCode 排放源编码
     * @return 因子 ×10000；未记录为 0
     */
    function lastFactorOf(string memory enterpriseId, string memory sourceCode) external view returns (uint256) {
        return _lastFactor[_key2(enterpriseId, sourceCode)];
    }

    // ---------------- 合规判定 ----------------

    /**
     * @notice 规则①（配额）：年累计排放量是否超出年度配额。
     * @dev 未配置配额（quota==0）直接视为合规并返回 "NO_QUOTA_CONFIGURED"；
     *      年累计优先读链上镜像 _yearTotal，镜像为 0 时无法区分「未同步」与「真实为 0」，统一按未同步处理，
     *      改用入参 fallbackYearToDateScaled；仅当 used > quota 时判为超限（等于配额合规）。
     * @param enterpriseId 企业编号
     * @param year 年份
     * @param fallbackYearToDateScaled 链下年累计兜底值，×100
     * @return code 判定码：0=合规，1=配额超限
     * @return compliant 是否合规
     * @return reason 判定原因标识（"OK" / "NO_QUOTA_CONFIGURED" / "QUOTA_EXCEEDED"）
     */
    function checkCompliance(string memory enterpriseId, uint256 year, uint256 fallbackYearToDateScaled)
        public view returns (uint8 code, bool compliant, string memory reason)
    {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        uint256 quota = _annualQuota[key];
        if (quota == 0) {
            return (OK, true, "NO_QUOTA_CONFIGURED");
        }
        uint256 mirrored = _yearTotal[key];
        uint256 used = mirrored == 0 ? fallbackYearToDateScaled : mirrored;
        if (used > quota) {
            return (QUOTA_EXCEEDED, false, "QUOTA_EXCEEDED");
        }
        return (OK, true, "OK");
    }

    /**
     * @notice 规则②（环比波动）：|环比基点| > 阈值 即不合规。
     * @dev 上期为 0 视为无基准，返回 (OK, true, 0, "NO_BASELINE")；deltaBp 已按 _deltaBp 对齐到 10bp，
     *      符号保留（负值表示下降）；阈值比较为严格大于，恰好等于阈值视为合规。
     * @param currentTotalScaled 本期合计 ×100
     * @param previousTotalScaled 上期合计 ×100
     * @return code 判定码：0=合规，2=环比超阈值
     * @return compliant 是否合规
     * @return deltaBp 环比变化基点（可为负）
     * @return reason 判定原因标识（"OK" / "NO_BASELINE" / "FLUCTUATION_EXCEEDED"）
     */
    function checkFluctuation(uint256 currentTotalScaled, uint256 previousTotalScaled)
        public view returns (uint8 code, bool compliant, int256 deltaBp, string memory reason)
    {
        if (previousTotalScaled == 0) {
            return (OK, true, 0, "NO_BASELINE");
        }
        deltaBp = _deltaBp(currentTotalScaled, previousTotalScaled);
        int256 absBp = deltaBp < 0 ? -deltaBp : deltaBp;
        if (uint256(absBp) > fluctuationThresholdBp) {
            return (FLUCTUATION_EXCEEDED, false, deltaBp, "FLUCTUATION_EXCEEDED");
        }
        return (OK, true, deltaBp, "OK");
    }

    /**
     * @notice 规则③（活动数据为 0，疑似漏填）。
     * @dev 纯函数，仅做等零判断，不做任何下限/容差判断；任何非 0 值都视为已填写。
     * @param activityDataScaled 活动数据 ×100
     * @return code 判定码：0=合规，3=活动数据为 0
     * @return compliant 是否合规
     * @return reason 判定原因标识（"OK" / "MISSING_ACTIVITY_DATA"）
     */
    function checkActivity(uint256 activityDataScaled) public pure returns (uint8 code, bool compliant, string memory reason) {
        if (activityDataScaled == 0) {
            return (MISSING_ACTIVITY_DATA, false, "MISSING_ACTIVITY_DATA");
        }
        return (OK, true, "OK");
    }

    /**
     * @notice 规则④（因子不符）：与链上最近一次记录的因子比较。
     * @dev 无历史（prev==0）视为合规；比较为精确不等判断，无相对误差容差，
     *      即因子任何差异都会命中，因此两侧必须先按 ×10000 同一口径定标。
     * @param enterpriseId 企业编号
     * @param sourceCode 排放源编码
     * @param factorScaled 本次使用的因子 ×10000
     * @return code 判定码：0=合规，4=因子不符
     * @return compliant 是否合规
     * @return previousFactorScaled 链上记录的上一因子 ×10000；0 表示无历史
     */
    function checkFactor(string memory enterpriseId, string memory sourceCode, uint256 factorScaled)
        public view returns (uint8 code, bool compliant, uint256 previousFactorScaled)
    {
        uint256 prev = _lastFactor[_key2(enterpriseId, sourceCode)];
        previousFactorScaled = prev;
        if (prev != 0 && prev != factorScaled) {
            return (FACTOR_MISMATCH, false, prev);
        }
        return (OK, true, prev);
    }

    /**
     * @notice 规则⑤（迟报）：报告期早于「当前月份 - lateReportMonths」即视为迟报。
     * @dev 月份以整数 YYYYMM 表示（如 202410），当前月份由后端传入。
     *      - 任一入参为 0（无报告期/无当前月）返回 (OK, true, "NO_PERIOD")；
     *      - 截止月计算失败（当前月非法，或月数不足以回退）返回 (OK, true, "NO_DEADLINE")，不做迟报判定；
     *      - 判定为严格小于：报告期恰好等于截止月视为合规。
     * @param periodYyyymm 报告期 YYYYMM
     * @param currentYyyymm 当前月 YYYYMM
     * @return code 判定码：0=合规，5=迟报
     * @return compliant 是否合规
     * @return reason 判定原因标识（"OK" / "NO_PERIOD" / "NO_DEADLINE" / "LATE_REPORT"）
     */
    function checkLateReport(uint256 periodYyyymm, uint256 currentYyyymm)
        public view returns (uint8 code, bool compliant, string memory reason)
    {
        if (periodYyyymm == 0 || currentYyyymm == 0) {
            return (OK, true, "NO_PERIOD");
        }
        uint256 deadline = _minusMonths(currentYyyymm, lateReportMonths);
        if (deadline == 0) {
            return (OK, true, "NO_DEADLINE");
        }
        if (periodYyyymm < deadline) {
            return (LATE_REPORT, false, "LATE_REPORT");
        }
        return (OK, true, "OK");
    }

    /**
     * @notice 一次调用完成全部五类链上规则判定，返回位掩码供后端按位生成告警。
     * @dev 后端按位解析：1 配额 / 2 环比 / 4 漏填 / 8 因子 / 16 迟报；多条命中时按位或累加。
     *      判定顺序与组合逻辑（与单条 check* 函数同口径，但为减少调用开销未复用函数调用，
     *      修改任一规则须同步单个 check* 函数与 Java / Stub 的本地复刻实现）：
     *      1) 配额：仅当配额非 0 时判定；年累计优先取 _yearTotal 镜像，镜像为 0 时用 yearToDateScaled 兜底；
     *         used > quota 置 FLAG_QUOTA，并把实际使用的年累计写入 yearToDateUsed；配额未配置时该返回值为 0。
     *      2) 环比：仅当 previousTotalScaled > 0 时计算 deltaBp 并判定，否则 deltaBp 保持 0（无基准不计算）。
     *      3) 活动数据：activityDataScaled == 0 置 FLAG_ACTIVITY。
     *      4) 因子：prev != 0 且 prev != factorScaled 置 FLAG_FACTOR，previousFactorScaled 恒返回链上旧值。
     *      5) 迟报：periodYyyymm 与 currentYyyymm 均非 0、截止月有效且 periodYyyymm < 截止月时置 FLAG_LATE。
     *      本函数为 view，不写任何状态；thresholdBp 恒返回当前生效的阈值，便于后端文案使用同一数值。
     * @param enterpriseId 企业编号
     * @param year 年份（用于配额与年累计的键）
     * @param sourceCode 排放源编码（用于因子基准的键）
     * @param currentTotalScaled 本期合计 ×100
     * @param previousTotalScaled 上期合计 ×100；0 表示无基准
     * @param yearToDateScaled 链下年累计兜底值 ×100
     * @param activityDataScaled 活动数据 ×100
     * @param factorScaled 本次排放因子 ×10000
     * @param periodYyyymm 报告期 YYYYMM
     * @param currentYyyymm 当前月 YYYYMM
     * @return flags 位掩码：1/2/4/8/16 的组合，0 表示全部合规
     * @return deltaBp 环比基点（对齐到 10bp，可为负）；未计算时为 0
     * @return previousFactorScaled 链上记录的旧因子 ×10000；0 表示无历史
     * @return quotaScaled 当前年度配额 ×100；未配置为 0
     * @return yearToDateUsed 本次配额判定实际使用的年累计 ×100；配额未配置时为 0
     * @return thresholdBp 本次判定使用的环比阈值（基点）
     */
    function checkAll(
        string memory enterpriseId,
        uint256 year,
        string memory sourceCode,
        uint256 currentTotalScaled,
        uint256 previousTotalScaled,
        uint256 yearToDateScaled,
        uint256 activityDataScaled,
        uint256 factorScaled,
        uint256 periodYyyymm,
        uint256 currentYyyymm
    ) public view returns (
        uint16 flags,
        int256 deltaBp,
        uint256 previousFactorScaled,
        uint256 quotaScaled,
        uint256 yearToDateUsed,
        uint256 thresholdBp
    ) {
        thresholdBp = fluctuationThresholdBp;
        // 配额
        bytes32 yearKey = _key2(enterpriseId, _uintToStr(year));
        uint256 quota = _annualQuota[yearKey];
        quotaScaled = quota;
        if (quota != 0) {
            uint256 mirrored = _yearTotal[yearKey];
            uint256 used = mirrored == 0 ? yearToDateScaled : mirrored;
            yearToDateUsed = used;
            if (used > quota) {
                flags |= FLAG_QUOTA;
            }
        }

        // 环比波动
        if (previousTotalScaled > 0) {
            deltaBp = _deltaBp(currentTotalScaled, previousTotalScaled);
            int256 absBp = deltaBp < 0 ? -deltaBp : deltaBp;
            if (uint256(absBp) > fluctuationThresholdBp) {
                flags |= FLAG_FLUCTUATION;
            }
        }

        // 活动数据为 0
        if (activityDataScaled == 0) {
            flags |= FLAG_ACTIVITY;
        }

        // 因子不符
        uint256 prevFactor = _lastFactor[_key2(enterpriseId, sourceCode)];
        previousFactorScaled = prevFactor;
        if (prevFactor != 0 && prevFactor != factorScaled) {
            flags |= FLAG_FACTOR;
        }

        // 迟报
        if (periodYyyymm != 0 && currentYyyymm != 0) {
            uint256 deadline = _minusMonths(currentYyyymm, lateReportMonths);
            if (deadline != 0 && periodYyyymm < deadline) {
                flags |= FLAG_LATE;
            }
        }
    }

    // ---------------- 内部工具 ----------------

    /**
     * @notice 计算环比变化基点（1bp = 0.01%），四舍五入到 BP_STEP（0.1%）。
     * @dev 与 Java 侧 BigDecimal(scale=1, HALF_UP) 的口径对齐，分两步取整：
     *      1) raw = diff * 10000 / previous，正负均按「远离零」方向加半个分母取整（非银行家舍入）；
     *      2) 再把 raw 对齐到 BP_STEP 的整数倍，同样按「远离零」方向取整，保证正负对称。
     *      previousScaled 为 0 时返回 0，由调用方决定是否视为无基准。
     * @param currentScaled 本期值（×100）
     * @param previousScaled 上期值（×100）
     * @return 环比基点，恒为 BP_STEP 的整数倍
     */
    function _deltaBp(uint256 currentScaled, uint256 previousScaled) private pure returns (int256) {
        if (previousScaled == 0) {
            return 0;
        }
        int256 diff = int256(currentScaled) - int256(previousScaled);
        int256 den = int256(previousScaled);
        int256 num = diff * 10000;
        int256 raw = num >= 0 ? (num + den / 2) / den : (num - den / 2) / den;
        int256 step = int256(BP_STEP);
        return raw >= 0 ? ((raw + step / 2) / step) * step : ((raw - step / 2) / step) * step;
    }

    /**
     * @notice yyyymm 往前推 months 个月，返回 YYYYMM。
     * @dev 先换算为「自 0 年 1 月起的线性月序」再回退，正确处理跨年；
     *      月份非法（0 或 >12）返回 0；回退后早于线性起点（月数不足）也返回 0，
     *      调用方以 0 表示「无有效截止月」。
     * @param yyyymm 基准月份，整数 YYYYMM
     * @param months 需要回退的月数
     * @return 回退后的 YYYYMM；无法计算时为 0
     */
    function _minusMonths(uint256 yyyymm, uint256 months) private pure returns (uint256) {
        uint256 y = yyyymm / 100;
        uint256 mo = yyyymm % 100;
        if (mo == 0 || mo > 12) {
            return 0;
        }
        uint256 total = y * 12 + (mo - 1);
        if (total < months) {
            return 0;
        }
        total -= months;
        return (total / 12) * 100 + (total % 12) + 1;
    }

    /**
     * @notice 构造两段式映射键：keccak256(abi.encodePacked(a, "|", b))。
     * @dev 用固定的 "|" 分隔，避免两段直接拼接造成的歧义（如 "ab"+"c" 与 "a"+"bc"）。
     *      若段内本身含 "|"，不同组合仍可能拼出同一字节串，因此 enterpriseId / sourceCode 不应包含分隔符。
     *      两个字符串均按原始字节参与哈希，不做大小写、去空格或 Unicode 归一化，
     *      因此 backend 写入与查询必须使用完全一致的字符串。
     * @param a 第一段（通常是 enterpriseId）
     * @param b 第二段（年份 / 报告期 / 排放源编码）
     * @return 32 字节哈希键
     */
    function _key2(string memory a, string memory b) private pure returns (bytes32) {
        return keccak256(abi.encodePacked(a, "|", b));
    }

    /**
     * @notice 把无符号整数转为十进制字符串（无前导零，0 转为 "0"）。
     * @dev 用于把年份统一成键的一部分，保证 setAnnualQuota 写入与查询侧键构造一致；
     *      仅在内部拼接键时使用，不对外暴露。
     * @param v 待转换的整数
     * @return 十进制字符串表示
     */
    function _uintToStr(uint256 v) private pure returns (string memory) {
        if (v == 0) {
            return "0";
        }
        uint256 t = v;
        uint256 n = 0;
        while (t != 0) {
            n++;
            t /= 10;
        }
        bytes memory b = new bytes(n);
        while (v != 0) {
            n--;
            b[n] = bytes1(uint8(48 + v % 10));
            v /= 10;
        }
        return string(b);
    }
}
