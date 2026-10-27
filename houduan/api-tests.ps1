# 企业碳排放认证与溯源系统 - 后端接口全量测试
# 用法: powershell -ExecutionPolicy Bypass -File api-tests.ps1
# 输出: 逐项 PASS/FAIL + 汇总; 存在失败时退出码为 1
#
# 【用途】
#   对运行中的后端做端到端冒烟/回归测试，共 9 组用例：
#     一、认证模块            四角色登录、错误密码、/me 鉴权
#     二、公共端（免登录）    企业/排放概览/示例证书/二维码解析/旧路径鉴权
#     三、认证记录接口        列表、按防伪码查询及大小写敏感性
#     四、角色访问限制        监管机构接口的角色准入（403/200）
#     五、监管机构 CRUD       必填校验、新增、修改、删除、重复删除 404
#     六、企业管理            列表、行业字典、单个查询、404
#     七、统计接口            监管/企业/审核三端概览与趋势
#     八、其他业务模块        排放记录、排放源、因子、报告、区块链、告警、审核机构
#     九、链模式与合规判定    chain health/stats、合规检查、阈值、配额、公开合规快照
#
# 【前置依赖】
#   1. 后端必须已在本机 8080 启动（$base 硬编码为 http://localhost:8080），
#      数据库已按 database/ 下脚本初始化并包含演示数据；
#   2. 默认 stub 链模式下，后端启动阶段由 StubChainSeeder 把业务库已有认证记录
#      （含示例证书 CR000001~CR000003）播种到离线桩，因此「二、公共端」的
#      sample-certs / qr-resolve、以及「三、认证记录接口」依赖播种完成；
#      若切换到 webase 模式而链不可达，相关链上用例会返回 502/503 而失败。
#   3. 用例只读为主，仅「五、监管机构 CRUD」会创建并删除一条
#      API_TEST_ORG 测试机构，测试结束后列表应恢复原状。
#
# 【退出码】
#   全部通过 exit 0；存在任一失败项时打印失败清单并 exit 1，便于 CI/批处理判断。
# ============================================================

# 控制台按 UTF-8 输出，避免中文用例名/响应体在 Windows PowerShell 5 下乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 未捕获的异常（如脚本自身语法错误）立即中止，避免带着错误状态继续跑用例
$ErrorActionPreference = 'Stop'

# 后端入口地址：脚本假定后端与测试同机运行，换端口需同步修改这里
$base = 'http://localhost:8080'
# 断言计数：pass/fail 分开累计，failures 保存失败用例名用于汇总输出
$script:passCount = 0
$script:failCount = 0
$script:failures = New-Object System.Collections.Generic.List[string]

# 统一的 HTTP 请求封装：支持 GET/POST 等，可携带 Bearer Token 与 JSON 请求体。
# 返回 @{ Status = HTTP 状态码; Body = 响应体文本 }，异常时尽量解析出状态码，无法解析则返回 -1。
# 要点：
#   · 请求体显式转成 UTF-8 字节，避免 PowerShell 默认编码把中文参数写坏；
#   · Invoke-WebRequest 对 4xx/5xx 会抛异常，这里在 catch 中取回真实状态码与响应体，
#     所以 400/401/403/404 等负向用例也能正常断言；
#   · 返回 -1 表示连后端都没连上（网络错误/连接拒绝），通常说明服务未启动。
function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body, [string]$Token)
    try {
        $headers = @{}
        if ($Token) { $headers['Authorization'] = "Bearer $Token" }
        $params = @{
            Method = $Method; Uri = $Uri; Headers = $headers
            ContentType = 'application/json'; UseBasicParsing = $true; TimeoutSec = 20
        }
        if ($null -ne $Body) { $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress)) }
        $resp = Invoke-WebRequest @params
        $bytes = $resp.RawContentStream.ToArray()
        $body = [System.Text.Encoding]::UTF8.GetString($bytes)
        return @{ Status = [int]$resp.StatusCode; Body = $body }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $content = $reader.ReadToEnd()
            return @{ Status = $status; Body = $content }
        }
        return @{ Status = -1; Body = $_.Exception.Message }
    }
}

# 断言 HTTP 状态码是否等于预期值，输出 PASS/FAIL 并累计计数。
# 失败时把用例名记入 $script:failures，并回显期望值与实际值，便于定位是权限、
# 参数校验还是路由问题；计数器的方括号序号为「已执行用例总数」。
function Test-Status {
    param([string]$Name, [int]$Actual, [int]$Expected)
    if ($Actual -eq $Expected) {
        $script:passCount++
        Write-Host ("PASS [{0,2}] {1}" -f $script:passCount, $Name)
    } else {
        $script:failCount++
        $script:failures.Add($Name)
        Write-Host ("FAIL [{0,2}] {1}  (期望 {2}, 实际 {3})" -f ($script:passCount + $script:failCount), $Name, $Expected, $Actual) -ForegroundColor Red
    }
}

# 断言布尔条件是否为真（用于校验响应体字段等非状态码类结果）。
# $Detail 一般传实际响应体，失败时直接打印，便于判断是字段缺失还是值不符。
function Test-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) {
        $script:passCount++
        Write-Host ("PASS [{0,2}] {1}" -f $script:passCount, $Name)
    } else {
        $script:failCount++
        $script:failures.Add($Name)
        Write-Host ("FAIL [{0,2}] {1}  {2}" -f ($script:passCount + $script:failCount), $Name, $Detail) -ForegroundColor Red
    }
}

# ------------------------------------------------------------
# 一、认证模块：四角色登录 → 错误密码 → 携带 token 访问 /me → 无 token 401
#    登录成功后按角色缓存 token（ent/reg/aud/adm），后续分组复用；
#    某个角色登录失败会导致依赖该 token 的后续用例连锁失败。
# ------------------------------------------------------------
Write-Host "===== 一、认证模块 =====" -ForegroundColor Cyan

# 1. 四角色登录
#    账号/密码须与当前数据库 users 表一致：四个演示账号统一为默认 123456；
#    key 用于把 token 存入 $tokens。
$roles = @(
    @{ username = 'enterprise01'; password = '123456'; role = 'enterprise'; key = 'ent' },
    @{ username = 'regulator01';  password = '123456'; role = 'regulator';  key = 'reg' },
    @{ username = 'auditor01';    password = '123456'; role = 'auditor';    key = 'aud' },
    @{ username = 'admin';        password = '123456'; role = 'admin';      key = 'adm' }
)
$tokens = @{}
foreach ($r in $roles) {
    $resp = Invoke-Api -Method Post -Uri "$base/api/auth/login" -Body $r
    Test-Status "$($r.role) 角色登录 ($($r.username))" $resp.Status 200
    if ($resp.Status -eq 200) {
        $tokens[$r.key] = (ConvertFrom-Json -InputObject $resp.Body).token
    }
}

# 2. 错误密码登录
$resp = Invoke-Api -Method Post -Uri "$base/api/auth/login" -Body @{ username = 'enterprise01'; password = 'wrong'; role = 'enterprise' }
Test-Status "错误密码登录返回 400" $resp.Status 400

# 3. /me 携带 token
$resp = Invoke-Api -Method Get -Uri "$base/api/auth/me" -Token $tokens['ent']
$me = $null
if ($resp.Status -eq 200) { $me = ConvertFrom-Json -InputObject $resp.Body }
Test-Status "/api/auth/me 携带 token 返回 200" $resp.Status 200
Test-True  "/api/auth/me 返回当前用户 enterprise01" ($me -and $me.username -eq 'enterprise01') "实际: $($resp.Body)"

# 4. 无 token 访问 /me
$resp = Invoke-Api -Method Get -Uri "$base/api/auth/me"
Test-Status "无 token 访问 /api/auth/me 返回 401" $resp.Status 401

# ------------------------------------------------------------
# 二、公共端（免登录）
#    /api/public/** 在拦截器白名单内，不带 token 也应可访问；
#    同时验证 sample-certs 固定只返回 3 条（防止遍历）、二维码解析对大小写敏感、
#    以及旧路径 /api/certifications/by-code 已不再免登录（应 401）。
# ------------------------------------------------------------
Write-Host "`n===== 二、公共端(免登录) =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/public/enterprises"
Test-Status "/api/public/enterprises 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/public/enterprise-emissions"
Test-Status "/api/public/enterprise-emissions 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/public/sample-certs"
Test-Status "/api/public/sample-certs 返回 200" $resp.Status 200
$samples = @()
if ($resp.Status -eq 200) { $parsed = ConvertFrom-Json -InputObject $resp.Body; $samples = @($parsed) }
Test-True "/api/public/sample-certs 固定返回 3 条" ($samples.Count -eq 3) "实际 $($samples.Count) 条"

# 取一个真实防伪码用于后续测试（注意：该值来自桩播种后的示例证书，
# 若后端未完成播种，$fingerprint 为空，依赖它的后续用例会被跳过或误判）
$fingerprint = ''
if ($samples.Count -gt 0 -and $samples[0].fingerprint) { $fingerprint = [string]$samples[0].fingerprint }
Test-True "示例证书包含 fingerprint 字段" ([bool]($fingerprint -ne '')) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve"
Test-Status "/api/public/qr-resolve 缺少参数返回 400" $resp.Status 400

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=NO_SUCH_FP_000"
Test-Status "/api/public/qr-resolve 不存在防伪码返回 404" $resp.Status 404

# 大小写敏感校验：防伪码按原样存储，转小写后应查不到（防止前端/后端归一化口径不一致）
$lower = $fingerprint.ToLower()
$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=$lower"
Test-Status "/api/public/qr-resolve 小写防伪码返回 404 (大小写敏感)" $resp.Status 404

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=$fingerprint"
Test-Status "/api/public/qr-resolve 正确防伪码返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-code/CR20240001"
Test-Status "旧 by-code 路径无 token 返回 401 (不再免登录)" $resp.Status 401

# ------------------------------------------------------------
# 三、认证记录接口（需登录）
#    用审核角色 token 访问列表与按防伪码查询；同样校验防伪码大小写敏感，
#    确保受保护的查询接口与公共端行为一致。
# ------------------------------------------------------------
Write-Host "`n===== 三、认证记录接口 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications" -Token $tokens['aud']
Test-Status "GET /api/certifications (审核token) 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-fingerprint/$fingerprint" -Token $tokens['aud']
Test-Status "GET /api/certifications/by-fingerprint 正确防伪码返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-fingerprint/$lower" -Token $tokens['aud']
Test-Status "GET /api/certifications/by-fingerprint 小写返回 404 (大小写敏感)" $resp.Status 404

# ------------------------------------------------------------
# 四、角色访问限制
#    /api/regulator-organizations 仅监管与 admin 可读：
#    企业/审核角色应 403，监管/admin 应 200。
# ------------------------------------------------------------
Write-Host "`n===== 四、角色访问限制 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['ent']
Test-Status "监管机构接口 企业token 返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['aud']
Test-Status "监管机构接口 审核token 返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['reg']
Test-Status "监管机构接口 监管token 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['adm']
Test-Status "监管机构接口 admin token 返回 200" $resp.Status 200

# ------------------------------------------------------------
# 五、监管机构 CRUD（写操作，会真实落库）
#    流程：缺字段 400 → 完整字段新增 200 → 按 id 修改 200 → 列表校验 → 删除 200
#          → 重复删除 404 → 列表校验已移除。
#    说明：测试数据使用 ASCII 名称并在结束后删除，避免污染演示数据。
# ------------------------------------------------------------
Write-Host "`n===== 五、监管机构 CRUD =====" -ForegroundColor Cyan

$incomplete = @{ name = 'API_TEST_ORG' }  # 缺 5 个必填字段
$resp = Invoke-Api -Method Post -Uri "$base/api/regulator-organizations" -Token $tokens['reg'] -Body $incomplete
Test-Status "监管机构新增缺少必填字段返回 400" $resp.Status 400

$full = @{
    name = 'API_TEST_ORG'; region = 'NorthRegion'; contact = 'API Tester'
    phone = '199-9999-9999'; address = '1 Test Road'; scope = 'API automation test'
    status = 'active'
}
$resp = Invoke-Api -Method Post -Uri "$base/api/regulator-organizations" -Token $tokens['reg'] -Body $full
Test-Status "监管机构完整字段新增返回 200" $resp.Status 200
$newId = ''
if ($resp.Status -eq 200) { $newId = ((ConvertFrom-Json -InputObject $resp.Body).id) }
Test-True "新增监管机构返回 id" ($newId -ne '') "实际: $($resp.Body)"

$full.name = 'API_TEST_ORG_UPDATED'
$resp = Invoke-Api -Method Put -Uri "$base/api/regulator-organizations/$newId" -Token $tokens['reg'] -Body $full
Test-Status "监管机构修改返回 200" $resp.Status 200
# 用服务端回显的名称做比对，避免脚本中文编码差异造成误判
$updatedName = ''
if ($resp.Status -eq 200) { $updatedName = (ConvertFrom-Json -InputObject $resp.Body).name }

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['reg']
$listAfterAdd = @()
$parsedAdd = ConvertFrom-Json -InputObject $resp.Body; $listAfterAdd = @($parsedAdd)
Test-True "列表中包含新机构(改后名称)" ((@($listAfterAdd | Where-Object { $_.name -eq $updatedName })).Count -ge 1) "期望名称: $updatedName"

$resp = Invoke-Api -Method Delete -Uri "$base/api/regulator-organizations/$newId" -Token $tokens['reg']
Test-Status "监管机构删除返回 200" $resp.Status 200

$resp = Invoke-Api -Method Delete -Uri "$base/api/regulator-organizations/$newId" -Token $tokens['reg']
Test-Status "重复删除返回 404" $resp.Status 404

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['reg']
$listAfterDel = @()
$parsedDel = ConvertFrom-Json -InputObject $resp.Body; $listAfterDel = @($parsedDel)
Test-True "删除后列表不含该机构" ((@($listAfterDel | Where-Object { $_.id -eq $newId })).Count -eq 0)

# ------------------------------------------------------------
# 六、企业管理
#    验证企业列表、行业字典、单条查询与不存在企业的 404；
#    均为监管角色可读的只读接口。
# ------------------------------------------------------------
Write-Host "`n===== 六、企业管理 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises" -Token $tokens['reg']
Test-Status "GET /api/enterprises 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/industries" -Token $tokens['reg']
Test-Status "GET /api/enterprises/industries 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/ENT20240001" -Token $tokens['reg']
Test-Status "GET /api/enterprises/ENT20240001 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/ENT99999999" -Token $tokens['reg']
Test-Status "GET 不存在企业返回 404" $resp.Status 404

# ------------------------------------------------------------
# 七、统计接口
#    按角色分别验证监管/企业/审核三端概览，企业端额外验证趋势接口；
#    只断言 200，具体指标口径由各自 Service 单测覆盖。
# ------------------------------------------------------------
Write-Host "`n===== 七、统计接口 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/regulator/overview" -Token $tokens['reg']
Test-Status "监管端统计 overview 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/enterprise/overview" -Token $tokens['ent']
Test-Status "企业端统计 overview 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/enterprise/trend" -Token $tokens['ent']
Test-Status "企业端统计 trend 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/auditor/overview" -Token $tokens['aud']
Test-Status "审核端统计 overview 返回 200" $resp.Status 200

# ------------------------------------------------------------
# 八、其他业务模块
#    逐一验证排放记录、排放源、因子、报告、区块链、告警、审核机构列表可用；
#    只校验状态码，防止模块路由或鉴权配置被改坏而未被发现。
# ------------------------------------------------------------
Write-Host "`n===== 八、其他业务模块 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/emission-records" -Token $tokens['ent']
Test-Status "排放记录列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/emission-sources" -Token $tokens['ent']
Test-Status "排放源列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/emission-factors" -Token $tokens['ent']
Test-Status "排放因子列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/report-records" -Token $tokens['aud']
Test-Status "报告记录列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/blockchain" -Token $tokens['reg']
Test-Status "区块链区块列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/blockchain/stats" -Token $tokens['reg']
Test-Status "区块链统计返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/alerts" -Token $tokens['reg']
Test-Status "告警列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/audit-organizations" -Token $tokens['reg']
Test-Status "审核机构列表返回 200" $resp.Status 200

# ============================================================
# 九、链模式与合规判定
#    blockchain/health 反映链节点连通性；stats 额外返回 mode 与 chainAvailable，
#    stub 模式下为离线桩，chainAvailable 可能为 false —— 只断言字段存在，不断言为 true。
#    compliance/check 返回 flags/items/onChain：flags 为合规问题列表、items 为逐项结果、
#    onChain 表示本次判定是否已上链；缺少企业编号时应 400。
#    threshold 仅监管/admin 可改（企业角色应 403）；公开合规快照按防伪码免登录查询。
# ============================================================
Write-Host "`n===== 九、链模式与合规判定 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/blockchain/health" -Token $tokens['reg']
Test-Status "/api/blockchain/health 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/blockchain/stats" -Token $tokens['reg']
Test-Status "/api/blockchain/stats 返回 200（含链模式）" $resp.Status 200
$stats = $null
try { $stats = $resp.Body | ConvertFrom-Json } catch { }
Test-True "/api/blockchain/stats 返回 mode 字段" ([bool]($null -ne $stats.mode)) "实际: $($resp.Body)"
Test-True "/api/blockchain/stats 返回 chainAvailable 字段" ([bool]($null -ne $stats.PSObject.Properties['chainAvailable'])) "实际: $($resp.Body)"

$checkBody = @{ enterpriseId = 'ENT20240001'; period = '2024-01'; sourceCode = 'S01'; activityData = 100; factor = 2.5 }
$resp = Invoke-Api -Method Post -Uri "$base/api/compliance/check" -Body $checkBody -Token $tokens['reg']
Test-Status "/api/compliance/check 返回 200" $resp.Status 200
$comp = $null
try { $comp = $resp.Body | ConvertFrom-Json } catch { }
Test-True "/api/compliance/check 返回 flags 字段" ([bool]($null -ne $comp.flags)) "实际: $($resp.Body)"
Test-True "/api/compliance/check 返回 onChain 字段" ([bool]($null -ne $comp.PSObject.Properties['onChain'])) "实际: $($resp.Body)"
Test-True "/api/compliance/check 返回 items 列表" ([bool]($null -ne $comp.items)) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/compliance/threshold" -Token $tokens['reg']
Test-Status "/api/compliance/threshold 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/compliance/quota?enterpriseId=ENT20240001&year=2024" -Token $tokens['reg']
Test-Status "/api/compliance/quota 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Put -Uri "$base/api/compliance/threshold" -Body @{ thresholdBp = 2500 } -Token $tokens['ent']
Test-Status "/api/compliance/threshold 企业角色修改返回 403" $resp.Status 403

$resp = Invoke-Api -Method Post -Uri "$base/api/compliance/check" -Body @{ period = '2024-01' } -Token $tokens['reg']
Test-Status "/api/compliance/check 缺少企业编号返回 400" $resp.Status 400

# 没有可用防伪码时跳过该用例（不计入失败），避免桩未播种时误报
if ($fingerprint) {
    $resp = Invoke-Api -Method Get -Uri "$base/api/public/compliance/$fingerprint"
    Test-Status "/api/public/compliance 免登录返回 200" $resp.Status 200
}

# ------------------------------------------------------------
# 十、CBAM 合规：产品目录/装置/产品排放/核查报告/申报包/统计
#    正向断言依赖 09_cbam.sql 的演示种子数据；申报包导出仅在已生成时校验，
#    未生成时跳过（不计入失败），避免空库误报。
# ------------------------------------------------------------
Write-Host "`n===== 十、CBAM 合规 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/goods" -Token $tokens['ent']
Test-Status "CBAM 产品目录返回 200" $resp.Status 200
$cbamGoods = @()
if ($resp.Status -eq 200) { $parsedGoods = ConvertFrom-Json -InputObject $resp.Body; $cbamGoods = @($parsedGoods) }
Test-True "CBAM 产品目录至少 6 条" ($cbamGoods.Count -ge 6) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/installations" -Token $tokens['ent']
Test-Status "CBAM 装置列表返回 200" $resp.Status 200
$cbamInst = @()
if ($resp.Status -eq 200) { $parsedInst = ConvertFrom-Json -InputObject $resp.Body; $cbamInst = @($parsedInst) }
Test-True "CBAM 装置列表至少 1 条" ($cbamInst.Count -ge 1) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/emissions?year=2026" -Token $tokens['ent']
Test-Status "CBAM 产品排放列表返回 200" $resp.Status 200
$cbamEm = @()
if ($resp.Status -eq 200) { $parsedEm = ConvertFrom-Json -InputObject $resp.Body; $cbamEm = @($parsedEm) }
Test-True "CBAM 产品排放至少 1 条" ($cbamEm.Count -ge 1) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/declarations?year=2026" -Token $tokens['ent']
Test-Status "CBAM 申报包列表返回 200" $resp.Status 200
$cbamDecls = @()
if ($resp.Status -eq 200) { $parsedDecls = ConvertFrom-Json -InputObject $resp.Body; $cbamDecls = @($parsedDecls) }
if ($cbamDecls.Count -gt 0) {
    $resp = Invoke-Api -Method Get -Uri "$base/api/cbam/declarations/$($cbamDecls[0].id)/export" -Token $tokens['ent']
    Test-Status "CBAM 申报包导出返回 200" $resp.Status 200
    Test-True "CBAM 申报包导出包含 cnCode 字段" ($resp.Body -like '*cnCode*') "实际: $($resp.Body.Substring(0, [Math]::Min(120, $resp.Body.Length)))"
}

$resp = Invoke-Api -Method Post -Uri "$base/api/cbam/declarations/generate" -Body @{ year = 2026 } -Token $tokens['reg']
Test-Status "CBAM 生成申报包 监管角色返回 403" $resp.Status 403

$resp = Invoke-Api -Method Post -Uri "$base/api/cbam/verifiers" -Body @{ name = 'x'; accreditationNo = 'x-1' } -Token $tokens['ent']
Test-Status "CBAM 新增核查机构 企业角色返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/verification-reports?year=2026" -Token $tokens['ent']
Test-Status "CBAM 核查报告列表返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/stats?year=2026" -Token $tokens['ent']
Test-Status "CBAM 统计 企业角色返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/stats?year=2026" -Token $tokens['aud']
Test-Status "CBAM 统计 审核角色返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/stats?year=2026" -Token $tokens['adm']
Test-Status "CBAM 统计 管理员返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/cbam/declarations/999999/export" -Token $tokens['ent']
Test-Status "CBAM 导出不存在的申报包返回 404" $resp.Status 404

# ===== 汇总 =====
# 汇总只统计已执行的断言（跳过项不计入总数）；有失败时逐条打印并返回非零退出码，
# 让 CI 或 .bat/.ps1 调用方无需解析日志即可判断成败。
$total = $script:passCount + $script:failCount
Write-Host ""
Write-Host ("===== 测试汇总: 共 {0} 项, 通过 {1}, 失败 {2} =====" -f $total, $script:passCount, $script:failCount) -ForegroundColor Cyan
if ($script:failCount -gt 0) {
    Write-Host "失败项:" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
exit 0
