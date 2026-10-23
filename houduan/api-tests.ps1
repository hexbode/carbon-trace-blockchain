# 企业碳排放认证与溯源系统 - 后端接口全量测试
# 用法: powershell -ExecutionPolicy Bypass -File api-tests.ps1
# 输出: 逐项 PASS/FAIL + 汇总; 存在失败时退出码为 1

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$base = 'http://localhost:8080'
$script:passCount = 0
$script:failCount = 0
$script:failures = New-Object System.Collections.Generic.List[string]

# 统一的 HTTP 请求封装：支持 GET/POST 等，可携带 Bearer Token 与 JSON 请求体。
# 返回 @{ Status = HTTP 状态码; Body = 响应体文本 }，异常时尽量解析出状态码，无法解析则返回 -1。
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

Write-Host "===== 一、认证模块 =====" -ForegroundColor Cyan

# 1. 四角色登录
$roles = @(
    @{ username = 'enterprise01'; password = '12345678abc'; role = 'enterprise'; key = 'ent' },
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

# 取一个真实防伪码用于后续测试
$fingerprint = ''
if ($samples.Count -gt 0 -and $samples[0].fingerprint) { $fingerprint = [string]$samples[0].fingerprint }
Test-True "示例证书包含 fingerprint 字段" ([bool]($fingerprint -ne '')) "实际: $($resp.Body)"

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve"
Test-Status "/api/public/qr-resolve 缺少参数返回 400" $resp.Status 400

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=NO_SUCH_FP_000"
Test-Status "/api/public/qr-resolve 不存在防伪码返回 404" $resp.Status 404

$lower = $fingerprint.ToLower()
$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=$lower"
Test-Status "/api/public/qr-resolve 小写防伪码返回 404 (大小写敏感)" $resp.Status 404

$resp = Invoke-Api -Method Get -Uri "$base/api/public/qr-resolve?fingerprint=$fingerprint"
Test-Status "/api/public/qr-resolve 正确防伪码返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-code/CR20240001"
Test-Status "旧 by-code 路径无 token 返回 401 (不再免登录)" $resp.Status 401

Write-Host "`n===== 三、认证记录接口 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications" -Token $tokens['aud']
Test-Status "GET /api/certifications (审核token) 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-fingerprint/$fingerprint" -Token $tokens['aud']
Test-Status "GET /api/certifications/by-fingerprint 正确防伪码返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/certifications/by-fingerprint/$lower" -Token $tokens['aud']
Test-Status "GET /api/certifications/by-fingerprint 小写返回 404 (大小写敏感)" $resp.Status 404

Write-Host "`n===== 四、角色访问限制 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['ent']
Test-Status "监管机构接口 企业token 返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['aud']
Test-Status "监管机构接口 审核token 返回 403" $resp.Status 403

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['reg']
Test-Status "监管机构接口 监管token 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/regulator-organizations" -Token $tokens['adm']
Test-Status "监管机构接口 admin token 返回 200" $resp.Status 200

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

Write-Host "`n===== 六、企业管理 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises" -Token $tokens['reg']
Test-Status "GET /api/enterprises 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/industries" -Token $tokens['reg']
Test-Status "GET /api/enterprises/industries 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/ENT20240001" -Token $tokens['reg']
Test-Status "GET /api/enterprises/ENT20240001 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/enterprises/ENT99999999" -Token $tokens['reg']
Test-Status "GET 不存在企业返回 404" $resp.Status 404

Write-Host "`n===== 七、统计接口 =====" -ForegroundColor Cyan

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/regulator/overview" -Token $tokens['reg']
Test-Status "监管端统计 overview 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/enterprise/overview" -Token $tokens['ent']
Test-Status "企业端统计 overview 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/enterprise/trend" -Token $tokens['ent']
Test-Status "企业端统计 trend 返回 200" $resp.Status 200

$resp = Invoke-Api -Method Get -Uri "$base/api/stats/auditor/overview" -Token $tokens['aud']
Test-Status "审核端统计 overview 返回 200" $resp.Status 200

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

# ===== 九、链模式与合规判定 =====
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

if ($fingerprint) {
    $resp = Invoke-Api -Method Get -Uri "$base/api/public/compliance/$fingerprint"
    Test-Status "/api/public/compliance 免登录返回 200" $resp.Status 200
}

# ===== 汇总 =====
$total = $script:passCount + $script:failCount
Write-Host ""
Write-Host ("===== 测试汇总: 共 {0} 项, 通过 {1}, 失败 {2} =====" -f $total, $script:passCount, $script:failCount) -ForegroundColor Cyan
if ($script:failCount -gt 0) {
    Write-Host "失败项:" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
exit 0
