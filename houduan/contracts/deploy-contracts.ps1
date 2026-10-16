# ============================================================
# 碳链通 · 合约部署脚本（FISCO BCOS + WeBASE-Front 1.5.x）
#
# 作用：编译 Solidity → 部署到链上 → 注册 ABI（便于 WeBASE 控制台解码交易）
#       → 可选把合约地址回写到 application-local.yml
#
# 前置：
#   1. FISCO BCOS 2.9.x 节点 + WeBASE-Front（默认 http://127.0.0.1:5002）已启动；
#   2. 生产模式需 WeBASE-Sign（Front 的 keyServer 已指向它），并先创建托管用户：
#         POST /WeBASE-Front/privateKey?type=2&userName=carbon-backend   → 得到 signUserId
#      本地开发模式可用 Front 测试用户（type=0）：
#         POST /WeBASE-Front/privateKey?type=0&userName=carbon-backend   → 得到 address
#
# 用法示例：
#   # 生产（WeBASE-Sign 托管私钥）
#   .\deploy-contracts.ps1 -Front http://127.0.0.1:5002 -SignUserId <signUserId> -AppAddress 0x...
#   # 本地开发（Front 本地私钥用户）
#   .\deploy-contracts.ps1 -Front http://127.0.0.1:5002 -UserAddress 0x... -AppAddress 0x...
# ============================================================
param(
    [string]$Front = 'http://127.0.0.1:5002',
    [int]$GroupId = 1,
    [string]$SignUserId = '',
    [string]$UserAddress = '',
    [string]$AppAddress = '',
    [string]$OutputFile = ''
)

$ErrorActionPreference = 'Stop'
$context = '/WeBASE-Front'
$contractDir = $PSScriptRoot

if (-not $SignUserId -and -not $UserAddress) {
    throw '必须提供 -SignUserId（生产）或 -UserAddress（本地开发）之一，用于交易签名。'
}
if (-not $AppAddress) {
    throw '必须提供 -AppAddress：业务后端账户地址，将作为合约 writer 写入初始权限。'
}

function Invoke-FrontPost([string]$Path, $Body) {
    $json = $Body | ConvertTo-Json -Depth 20
    Write-Host "POST $Path" -ForegroundColor DarkGray
    return Invoke-RestMethod -Method Post -Uri "$Front$context$Path" -ContentType 'application/json' -Body $json
}

function Compile-Contract([string]$Name, [string]$File) {
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($File))
    $resp = Invoke-FrontPost '/contract/contractCompile' @{ contractName = $Name; solidityBase64 = $base64 }
    if (-not $resp.bytecodeBin -or -not $resp.abiInfo) {
        throw "合约 $Name 编译失败：$($resp | ConvertTo-Json -Depth 6)"
    }
    Write-Host "  编译成功：$Name" -ForegroundColor Green
    return $resp
}

function Deploy-Contract([string]$Name, $Compiled, [object[]]$CtorParams) {
    $body = @{
        groupId         = $GroupId
        contractName    = $Name
        abiInfo         = $Compiled.abiInfo
        bytecodeBin     = $Compiled.bytecodeBin
        funcParam       = @($CtorParams)
        contractSource  = ''
        contractPath    = ''
        version         = ''
    }
    if ($SignUserId) {
        $body['signUserId'] = $SignUserId
        $resp = Invoke-FrontPost '/contract/deployWithSign' $body
    } else {
        $body['user'] = $UserAddress
        $resp = Invoke-FrontPost '/contract/deploy' $body
    }
    $address = ($resp | Out-String).Trim().Trim('"')
    if ($address -notmatch '^0x[0-9a-fA-F]{40}$') {
        throw "合约 $Name 部署失败，未返回合法地址：$address"
    }
    Write-Host "  部署成功：$Name -> $address" -ForegroundColor Green
    return $address
}

function Register-Abi([string]$Name, [string]$Address, $Compiled) {
    Invoke-FrontPost '/contract/abiInfo' @{
        groupId      = $GroupId
        contractName = $Name
        address      = $Address
        abiInfo      = $Compiled.abiInfo
        contractBin  = $Compiled.contractBin
    } | Out-Null
    Write-Host "  ABI 已注册：$Name" -ForegroundColor Green
}

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  碳链通 · 合约部署'
Write-Host "  Front       = $Front"
Write-Host "  GroupId     = $GroupId"
Write-Host "  签名方式    = $(if ($SignUserId) { "WeBASE-Sign ($SignUserId)" } else { "本地用户 ($UserAddress)" })"
Write-Host "  应用账户    = $AppAddress"
Write-Host '============================================' -ForegroundColor Cyan

# 1) 编译
Write-Host '[1/4] 编译合约 ...'
$complianceCompiled = Compile-Contract 'CarbonCompliance' (Join-Path $contractDir 'CarbonCompliance.sol')
$registryCompiled = Compile-Contract 'CarbonCertRegistry' (Join-Path $contractDir 'CarbonCertRegistry.sol')

# 2) 部署：合规合约无构造参数；存证合约构造参数为初始 writer 地址数组
Write-Host '[2/4] 部署合约 ...'
$complianceAddress = Deploy-Contract 'CarbonCompliance' $complianceCompiled @()
$registryAddress = Deploy-Contract 'CarbonCertRegistry' $registryCompiled @(, @($AppAddress))

# 3) 注册 ABI
Write-Host '[3/4] 注册 ABI ...'
Register-Abi 'CarbonCompliance' $complianceAddress $complianceCompiled
Register-Abi 'CarbonCertRegistry' $registryAddress $registryCompiled

# 4) 输出配置
Write-Host '[4/4] 完成。请把下列值写入配置（或环境变量）：' -ForegroundColor Yellow
Write-Host "  carbon.chain.mode=webase"
Write-Host "  carbon.chain.contracts.compliance.address=$complianceAddress"
Write-Host "  carbon.chain.contracts.registry.address=$registryAddress"
Write-Host "  # 或环境变量：CARBON_CHAIN_COMPLIANCE_ADDRESS / CARBON_CHAIN_REGISTRY_ADDRESS"

if ($OutputFile) {
    $content = @(
        '# 由 deploy-contracts.ps1 生成，请勿提交到仓库（真实环境配置）',
        'carbon:',
        '  chain:',
        '    mode: webase',
        '    webase:',
        $(if ($SignUserId) { "      sign-user-id: $SignUserId" } else { "      user-address: $UserAddress" }),
        '    contracts:',
        '      registry:',
        "        address: $registryAddress",
        '      compliance:',
        "        address: $complianceAddress"
    ) -join "`n"
    # 以「无 BOM 的 UTF-8」写配置，避免 YAML 解析受 BOM 影响
    [System.IO.File]::WriteAllText(
        (Join-Path (Get-Location) $OutputFile),
        $content,
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  已写入：$OutputFile" -ForegroundColor Green
}
