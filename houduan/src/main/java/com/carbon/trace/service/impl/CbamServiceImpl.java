package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.model.ChainAnchor;
import com.carbon.trace.chain.support.Keccak256;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.cbam.CbamOutboxService;
import com.carbon.trace.entity.CbamDeclaration;
import com.carbon.trace.entity.CbamGoods;
import com.carbon.trace.entity.CbamInstallation;
import com.carbon.trace.entity.CbamProductEmission;
import com.carbon.trace.entity.CbamReconciliationLog;
import com.carbon.trace.entity.CbamVerificationReport;
import com.carbon.trace.entity.CbamVerifier;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.mapper.CbamDataVersionMapper;
import com.carbon.trace.mapper.CbamDeclarationMapper;
import com.carbon.trace.mapper.CbamGoodsMapper;
import com.carbon.trace.mapper.CbamInstallationMapper;
import com.carbon.trace.mapper.CbamProductEmissionMapper;
import com.carbon.trace.mapper.CbamReconciliationLogMapper;
import com.carbon.trace.mapper.CbamVerificationReportMapper;
import com.carbon.trace.mapper.CbamVerifierMapper;
import com.carbon.trace.mapper.EnterpriseMapper;
import com.carbon.trace.service.CbamService;
import com.carbon.trace.service.ChainSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CBAM 合规服务实现（P0）。
 *
 * 关键实现约定：
 *  1. 排放量 = 产量 × 强度，统一在服务端四舍五入（强度 4 位、排放 3 位），不信任前端汇总值；
 *  2. 申报包按企业 + 年度唯一，JSON 快照冻结当时的明细与完整性结论；
 *  3. 实际排放要求同年度同装置存在 verified 核查报告，默认值数据不强制；
 *  4. 申报包状态机：draft → submitted → approved / returned（returned 可重新生成并再次提交）。
 */
@Service
public class CbamServiceImpl implements CbamService {

    private static final Logger log = LoggerFactory.getLogger(CbamServiceImpl.class);

    private final CbamGoodsMapper goodsMapper;
    private final CbamInstallationMapper installationMapper;
    private final CbamProductEmissionMapper emissionMapper;
    private final CbamVerifierMapper verifierMapper;
    private final CbamVerificationReportMapper reportMapper;
    private final CbamDeclarationMapper declarationMapper;
    private final CbamDataVersionMapper dataVersionMapper;
    private final CbamReconciliationLogMapper reconciliationLogMapper;
    private final CbamOutboxService outboxService;
    private final EnterpriseMapper enterpriseMapper;
    private final ObjectMapper objectMapper;
    private final ChainGateway chainGateway;
    private final ChainSyncService chainSyncService;

    public CbamServiceImpl(CbamGoodsMapper goodsMapper,
                           CbamInstallationMapper installationMapper,
                           CbamProductEmissionMapper emissionMapper,
                           CbamVerifierMapper verifierMapper,
                           CbamVerificationReportMapper reportMapper,
                           CbamDeclarationMapper declarationMapper,
                           CbamDataVersionMapper dataVersionMapper,
                           CbamReconciliationLogMapper reconciliationLogMapper,
                           CbamOutboxService outboxService,
                           EnterpriseMapper enterpriseMapper,
                           ObjectMapper objectMapper,
                           ChainGateway chainGateway,
                           ChainSyncService chainSyncService) {
        this.goodsMapper = goodsMapper;
        this.installationMapper = installationMapper;
        this.emissionMapper = emissionMapper;
        this.verifierMapper = verifierMapper;
        this.reportMapper = reportMapper;
        this.declarationMapper = declarationMapper;
        this.dataVersionMapper = dataVersionMapper;
        this.reconciliationLogMapper = reconciliationLogMapper;
        this.outboxService = outboxService;
        this.enterpriseMapper = enterpriseMapper;
        this.objectMapper = objectMapper;
        this.chainGateway = chainGateway;
        this.chainSyncService = chainSyncService;
    }

    // ==================== 产品目录 ====================

    @Override
    public List<CbamGoods> listGoods(String sector, String keyword) {
        LambdaQueryWrapper<CbamGoods> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(sector)) {
            wrapper.eq(CbamGoods::getSector, sector);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(CbamGoods::getCnCode, kw).or().like(CbamGoods::getName, kw));
        }
        wrapper.orderByAsc(CbamGoods::getId);
        return goodsMapper.selectList(wrapper);
    }

    @Override
    public CbamGoods createGoods(CbamGoods goods) {
        if (goods == null || !StringUtils.hasText(goods.getCnCode())
                || !StringUtils.hasText(goods.getName()) || !StringUtils.hasText(goods.getSector())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CN 编码、产品名称与 CBAM 行业均为必填");
        }
        Long duplicate = goodsMapper.selectCount(new LambdaQueryWrapper<CbamGoods>()
                .eq(CbamGoods::getCnCode, goods.getCnCode().trim()));
        if (duplicate != null && duplicate > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CN 编码已存在：" + goods.getCnCode());
        }
        goods.setId(null);
        goods.setCnCode(goods.getCnCode().trim());
        goods.setName(goods.getName().trim());
        goods.setDefaultDirect(nz(goods.getDefaultDirect()));
        goods.setDefaultIndirect(nz(goods.getDefaultIndirect()));
        goods.setStatus(StringUtils.hasText(goods.getStatus()) ? goods.getStatus() : "active");
        goods.setCreatedAt(LocalDateTime.now());
        goodsMapper.insert(goods);
        return goodsMapper.selectById(goods.getId());
    }

    @Override
    public CbamGoods updateGoods(Integer id, CbamGoods goods) {
        CbamGoods existing = requireGoods(id);
        if (goods == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "产品信息不能为空");
        }
        if (StringUtils.hasText(goods.getCnCode()) && !goods.getCnCode().trim().equals(existing.getCnCode())) {
            Long duplicate = goodsMapper.selectCount(new LambdaQueryWrapper<CbamGoods>()
                    .eq(CbamGoods::getCnCode, goods.getCnCode().trim()));
            if (duplicate != null && duplicate > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CN 编码已存在：" + goods.getCnCode());
            }
            existing.setCnCode(goods.getCnCode().trim());
        }
        if (StringUtils.hasText(goods.getName())) {
            existing.setName(goods.getName().trim());
        }
        if (StringUtils.hasText(goods.getSector())) {
            existing.setSector(goods.getSector());
        }
        if (goods.getDefaultDirect() != null) {
            existing.setDefaultDirect(goods.getDefaultDirect());
        }
        if (goods.getDefaultIndirect() != null) {
            existing.setDefaultIndirect(goods.getDefaultIndirect());
        }
        if (StringUtils.hasText(goods.getStatus())) {
            existing.setStatus(goods.getStatus());
        }
        goodsMapper.updateById(existing);
        return goodsMapper.selectById(id);
    }

    @Override
    public void deleteGoods(Integer id) {
        requireGoods(id);
        Long used = emissionMapper.selectCount(new LambdaQueryWrapper<CbamProductEmission>()
                .eq(CbamProductEmission::getGoodsId, id));
        if (used != null && used > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该产品已被 CBAM 排放记录引用，不能删除");
        }
        goodsMapper.deleteById(id);
    }

    // ==================== 生产装置 ====================

    @Override
    public List<CbamInstallation> listInstallations(String enterpriseId) {
        LambdaQueryWrapper<CbamInstallation> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(CbamInstallation::getEnterpriseId, enterpriseId);
        }
        wrapper.orderByAsc(CbamInstallation::getId);
        return installationMapper.selectList(wrapper);
    }

    @Override
    public CbamInstallation getInstallation(Integer id) {
        return requireInstallation(id);
    }

    @Override
    public CbamInstallation createInstallation(CbamInstallation installation) {
        if (installation == null || !StringUtils.hasText(installation.getEnterpriseId())
                || !StringUtils.hasText(installation.getName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号与装置名称均为必填");
        }
        requireEnterprise(installation.getEnterpriseId());
        installation.setId(null);
        installation.setEnterpriseId(installation.getEnterpriseId().trim());
        installation.setName(installation.getName().trim());
        installation.setCountry(StringUtils.hasText(installation.getCountry()) ? installation.getCountry() : "CN");
        installation.setStatus(StringUtils.hasText(installation.getStatus()) ? installation.getStatus() : "active");
        installation.setCreatedAt(LocalDateTime.now());
        installationMapper.insert(installation);
        return installationMapper.selectById(installation.getId());
    }

    @Override
    public CbamInstallation updateInstallation(Integer id, CbamInstallation installation) {
        CbamInstallation existing = requireInstallation(id);
        if (installation == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "装置信息不能为空");
        }
        if (StringUtils.hasText(installation.getName())) {
            existing.setName(installation.getName().trim());
        }
        if (StringUtils.hasText(installation.getCountry())) {
            existing.setCountry(installation.getCountry());
        }
        if (installation.getProductionRoute() != null) {
            existing.setProductionRoute(installation.getProductionRoute());
        }
        if (installation.getAddress() != null) {
            existing.setAddress(installation.getAddress());
        }
        if (StringUtils.hasText(installation.getStatus())) {
            existing.setStatus(installation.getStatus());
        }
        installationMapper.updateById(existing);
        return installationMapper.selectById(id);
    }

    @Override
    public void deleteInstallation(Integer id) {
        requireInstallation(id);
        Long used = emissionMapper.selectCount(new LambdaQueryWrapper<CbamProductEmission>()
                .eq(CbamProductEmission::getInstallationId, id));
        Long reports = reportMapper.selectCount(new LambdaQueryWrapper<CbamVerificationReport>()
                .eq(CbamVerificationReport::getInstallationId, id));
        if ((used != null && used > 0) || (reports != null && reports > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该装置已被排放记录或核查报告引用，不能删除");
        }
        installationMapper.deleteById(id);
    }

    // ==================== 产品级排放 ====================

    @Override
    public List<CbamProductEmission> listEmissions(String enterpriseId, Integer year, Integer goodsId) {
        LambdaQueryWrapper<CbamProductEmission> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(CbamProductEmission::getEnterpriseId, enterpriseId);
        }
        if (year != null) {
            wrapper.eq(CbamProductEmission::getReportYear, year);
        }
        if (goodsId != null) {
            wrapper.eq(CbamProductEmission::getGoodsId, goodsId);
        }
        wrapper.orderByDesc(CbamProductEmission::getReportYear).orderByDesc(CbamProductEmission::getId);
        return emissionMapper.selectList(wrapper);
    }

    @Override
    public CbamProductEmission getEmission(Long id) {
        return requireEmission(id);
    }

    @Override
    @Transactional
    public CbamProductEmission createEmission(CbamProductEmission emission) {
        if (emission == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "排放记录不能为空");
        }
        normalizeEmission(emission, emission.getEnterpriseId());
        emission.setId(null);
        emission.setCreatedAt(LocalDateTime.now());
        emission.setUpdatedAt(LocalDateTime.now());
        emissionMapper.insert(emission);
        bumpVersion(emission.getEnterpriseId(), emission.getReportYear());
        return emissionMapper.selectById(emission.getId());
    }

    @Override
    @Transactional
    public CbamProductEmission updateEmission(Long id, CbamProductEmission emission) {
        CbamProductEmission existing = requireEmission(id);
        if (emission == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "排放记录不能为空");
        }
        Integer oldYear = existing.getReportYear();
        normalizeEmission(emission, existing.getEnterpriseId());
        emission.setId(id);
        emission.setEnterpriseId(existing.getEnterpriseId());
        emission.setCreatedAt(existing.getCreatedAt());
        emission.setUpdatedAt(LocalDateTime.now());
        emissionMapper.updateById(emission);
        bumpVersion(existing.getEnterpriseId(), oldYear);
        if (!oldYear.equals(emission.getReportYear())) {
            bumpVersion(existing.getEnterpriseId(), emission.getReportYear());
        }
        return emissionMapper.selectById(id);
    }

    @Override
    @Transactional
    public void deleteEmission(Long id) {
        CbamProductEmission existing = requireEmission(id);
        emissionMapper.deleteById(id);
        bumpVersion(existing.getEnterpriseId(), existing.getReportYear());
    }

    /**
     * 校验并重算一条排放记录：设备归属、方法学、强度与总量。
     *
     * @param emission     待落库记录（请求体）
     * @param enterpriseId 实际归属企业（企业端由令牌强制，更新时取库中原值）
     */
    private void normalizeEmission(CbamProductEmission emission, String enterpriseId) {
        if (!StringUtils.hasText(enterpriseId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号不能为空");
        }
        requireEnterprise(enterpriseId);
        if (emission.getReportYear() == null
                || emission.getReportYear() < 2000 || emission.getReportYear() > 2100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报告年度不合法");
        }
        if (emission.getGoodsId() == null || emission.getInstallationId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CBAM 产品与生产装置均为必填");
        }
        CbamGoods goods = requireGoods(emission.getGoodsId());
        if ("inactive".equals(goods.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该 CBAM 产品已停用");
        }
        CbamInstallation installation = requireInstallation(emission.getInstallationId());
        if (!enterpriseId.equals(installation.getEnterpriseId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生产装置不属于当前企业");
        }
        BigDecimal quantity = emission.getQuantity();
        if (quantity == null || quantity.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "年度产量不能为负");
        }
        quantity = quantity.setScale(3, RoundingMode.HALF_UP);

        String method = StringUtils.hasText(emission.getDataMethod()) ? emission.getDataMethod() : "actual";
        if (!"actual".equals(method) && !"default".equals(method)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "数据方式仅支持 actual 或 default");
        }
        BigDecimal direct;
        BigDecimal indirect;
        if ("default".equals(method)) {
            direct = nz(goods.getDefaultDirect());
            indirect = nz(goods.getDefaultIndirect());
        } else {
            direct = nz(emission.getDirectIntensity());
            indirect = nz(emission.getIndirectIntensity());
            if (direct.signum() < 0 || indirect.signum() < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "排放强度不能为负");
            }
        }
        direct = direct.setScale(4, RoundingMode.HALF_UP);
        indirect = indirect.setScale(4, RoundingMode.HALF_UP);
        BigDecimal carbonPrice = nz(emission.getCarbonPrice());
        if (carbonPrice.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "已付碳价不能为负");
        }

        emission.setEnterpriseId(enterpriseId);
        emission.setDataMethod(method);
        emission.setQuantity(quantity);
        emission.setDirectIntensity(direct);
        emission.setIndirectIntensity(indirect);
        emission.setDirectEmissions(quantity.multiply(direct).setScale(3, RoundingMode.HALF_UP));
        emission.setIndirectEmissions(quantity.multiply(indirect).setScale(3, RoundingMode.HALF_UP));
        emission.setCarbonPrice(carbonPrice.setScale(2, RoundingMode.HALF_UP));
        if (emission.getRemark() != null) {
            emission.setRemark(emission.getRemark().trim());
        }
    }

    // ==================== 核查机构与报告 ====================

    @Override
    public List<CbamVerifier> listVerifiers() {
        return verifierMapper.selectList(new LambdaQueryWrapper<CbamVerifier>()
                .orderByAsc(CbamVerifier::getId));
    }

    @Override
    public CbamVerifier createVerifier(CbamVerifier verifier) {
        if (verifier == null || !StringUtils.hasText(verifier.getName())
                || !StringUtils.hasText(verifier.getAccreditationNo())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "机构名称与认可编号均为必填");
        }
        Long duplicate = verifierMapper.selectCount(new LambdaQueryWrapper<CbamVerifier>()
                .eq(CbamVerifier::getAccreditationNo, verifier.getAccreditationNo().trim()));
        if (duplicate != null && duplicate > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "认可编号已存在：" + verifier.getAccreditationNo());
        }
        verifier.setId(null);
        verifier.setName(verifier.getName().trim());
        verifier.setAccreditationNo(verifier.getAccreditationNo().trim());
        verifier.setStatus(StringUtils.hasText(verifier.getStatus()) ? verifier.getStatus() : "active");
        verifier.setCreatedAt(LocalDateTime.now());
        verifierMapper.insert(verifier);
        return verifierMapper.selectById(verifier.getId());
    }

    @Override
    public List<CbamVerificationReport> listVerificationReports(String enterpriseId, Integer year) {
        LambdaQueryWrapper<CbamVerificationReport> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(CbamVerificationReport::getEnterpriseId, enterpriseId);
        }
        if (year != null) {
            wrapper.eq(CbamVerificationReport::getReportYear, year);
        }
        wrapper.orderByDesc(CbamVerificationReport::getReportYear).orderByDesc(CbamVerificationReport::getId);
        return reportMapper.selectList(wrapper);
    }

    @Override
    public CbamVerificationReport createVerificationReport(CbamVerificationReport report) {
        if (report == null || !StringUtils.hasText(report.getEnterpriseId())
                || report.getInstallationId() == null || report.getReportYear() == null
                || report.getVerifierId() == null || !StringUtils.hasText(report.getReportNo())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业、装置、年度、核查机构与报告编号均为必填");
        }
        requireEnterprise(report.getEnterpriseId());
        CbamInstallation installation = requireInstallation(report.getInstallationId());
        if (!report.getEnterpriseId().equals(installation.getEnterpriseId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "生产装置不属于该企业");
        }
        CbamVerifier verifier = verifierMapper.selectById(report.getVerifierId());
        if (verifier == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "核查机构不存在");
        }
        String conclusion = StringUtils.hasText(report.getConclusion()) ? report.getConclusion() : "verified";
        if (!"verified".equals(conclusion) && !"not_verified".equals(conclusion)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "核查结论仅支持 verified 或 not_verified");
        }
        Long duplicate = reportMapper.selectCount(new LambdaQueryWrapper<CbamVerificationReport>()
                .eq(CbamVerificationReport::getEnterpriseId, report.getEnterpriseId())
                .eq(CbamVerificationReport::getInstallationId, report.getInstallationId())
                .eq(CbamVerificationReport::getReportYear, report.getReportYear())
                .eq(CbamVerificationReport::getReportNo, report.getReportNo().trim()));
        if (duplicate != null && duplicate > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "同一装置年度下的报告编号已存在");
        }
        report.setId(null);
        report.setConclusion(conclusion);
        report.setReportNo(report.getReportNo().trim());
        report.setIssuedAt(report.getIssuedAt() == null ? LocalDate.now() : report.getIssuedAt());
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
        return reportMapper.selectById(report.getId());
    }

    // ==================== 年度申报包 ====================

    @Override
    @Transactional
    public CbamDeclaration generateDeclaration(String enterpriseId, Integer year, String importerName) {
        if (!StringUtils.hasText(enterpriseId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号不能为空");
        }
        if (year == null || year < 2000 || year > 2100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申报年度不合法");
        }
        Enterprise enterprise = requireEnterprise(enterpriseId);
        List<CbamProductEmission> emissions = listEmissions(enterpriseId, year, null);
        if (emissions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "本年度暂无 CBAM 产品排放数据，无法生成申报包");
        }
        CbamDeclaration existing = declarationMapper.selectOne(new LambdaQueryWrapper<CbamDeclaration>()
                .eq(CbamDeclaration::getEnterpriseId, enterpriseId)
                .eq(CbamDeclaration::getReportYear, year));
        if (existing != null && ("submitted".equals(existing.getStatus()) || "approved".equals(existing.getStatus()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申报包已提交或已通过，不能重新生成");
        }

        Map<Integer, CbamVerificationReport> verifiedByInstallation = verifiedReports(enterpriseId, year);
        Map<ItemKey, ItemAgg> grouped = new LinkedHashMap<>();
        for (CbamProductEmission emission : emissions) {
            ItemKey key = new ItemKey(emission.getGoodsId(), emission.getInstallationId());
            ItemAgg agg = grouped.computeIfAbsent(key, k -> new ItemAgg(emission.getGoodsId(), emission.getInstallationId()));
            agg.add(emission);
        }

        Set<String> missing = new LinkedHashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalDirect = BigDecimal.ZERO;
        BigDecimal totalIndirect = BigDecimal.ZERO;
        BigDecimal totalCarbonPrice = BigDecimal.ZERO;

        for (ItemAgg agg : grouped.values()) {
            CbamGoods goods = requireGoods(agg.goodsId);
            CbamInstallation installation = requireInstallation(agg.installationId);
            BigDecimal total = agg.direct.add(agg.indirect);
            if (agg.quantity.signum() <= 0) {
                missing.add("产品「" + goods.getCnCode() + "」申报数量为 0");
            }
            if (total.signum() == 0) {
                missing.add("产品「" + goods.getCnCode() + "」隐含排放为 0");
            }
            CbamVerificationReport report = null;
            if (agg.actualUsed) {
                report = verifiedByInstallation.get(agg.installationId);
                if (report == null) {
                    missing.add("装置「" + installation.getName() + "」的实际排放缺少有效核查报告");
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cnCode", goods.getCnCode());
            item.put("goodsName", goods.getName());
            item.put("sector", goods.getSector());
            item.put("installation", installationBrief(installation));
            item.put("quantity", agg.quantity.setScale(3, RoundingMode.HALF_UP));
            item.put("directEmissions", agg.direct.setScale(3, RoundingMode.HALF_UP));
            item.put("indirectEmissions", agg.indirect.setScale(3, RoundingMode.HALF_UP));
            item.put("totalEmissions", total.setScale(3, RoundingMode.HALF_UP));
            item.put("dataMethods", new ArrayList<>(agg.methods));
            item.put("carbonPricePaid", agg.carbonPrice.setScale(2, RoundingMode.HALF_UP));
            item.put("verification", report == null ? null : verificationBrief(report));
            items.add(item);

            totalQuantity = totalQuantity.add(agg.quantity);
            totalDirect = totalDirect.add(agg.direct);
            totalIndirect = totalIndirect.add(agg.indirect);
            totalCarbonPrice = totalCarbonPrice.add(agg.carbonPrice);
        }

        CbamDeclaration declaration = existing == null ? new CbamDeclaration() : existing;
        declaration.setEnterpriseId(enterpriseId);
        declaration.setReportYear(year);
        if (StringUtils.hasText(importerName)) {
            declaration.setImporterName(importerName.trim());
        }
        declaration.setTotalQuantity(totalQuantity.setScale(3, RoundingMode.HALF_UP));
        declaration.setDirectEmissions(totalDirect.setScale(3, RoundingMode.HALF_UP));
        declaration.setIndirectEmissions(totalIndirect.setScale(3, RoundingMode.HALF_UP));
        declaration.setTotalEmissions(totalDirect.add(totalIndirect).setScale(3, RoundingMode.HALF_UP));
        declaration.setCarbonPrice(totalCarbonPrice.setScale(2, RoundingMode.HALF_UP));
        declaration.setCompleteness(missing.isEmpty() ? "complete" : "incomplete");
        // 用空串而不是 null：MyBatis-Plus 默认忽略 null 字段，旧缺失项会残留
        declaration.setMissingItems(missing.isEmpty() ? "" : truncate(String.join("；", missing), 600));
        declaration.setStatus("draft");
        declaration.setReviewComment("");
        declaration.setDataVersion(currentVersion(enterpriseId, year));
        declaration.setDeclarationNo(existing != null && StringUtils.hasText(existing.getDeclarationNo())
                ? existing.getDeclarationNo() : "CBAM-" + year + "-" + enterpriseId);
        String packageJson = toJson(buildPackage(declaration, enterprise, items, missing));
        declaration.setPackageJson(packageJson);
        // 快照哈希在生成时固化，提交时只把该哈希与数据版本锚定上链
        declaration.setSnapshotHash(Keccak256.hashHex(packageJson));
        // 空串而非 null：MyBatis-Plus 默认忽略 null，重新生成时必须显式清掉上一次锚点
        declaration.setAnchorTxHash("");
        declaration.setAnchorBlock(0L);
        LocalDateTime now = LocalDateTime.now();
        declaration.setUpdatedAt(now);
        if (existing == null) {
            declaration.setCreatedAt(now);
            declarationMapper.insert(declaration);
        } else {
            declarationMapper.updateById(declaration);
        }
        // anchored_at 无法用实体 null 清空（updateById 忽略 null），单独用 UpdateWrapper 置空
        declarationMapper.update(null, new LambdaUpdateWrapper<CbamDeclaration>()
                .eq(CbamDeclaration::getId, declaration.getId())
                .set(CbamDeclaration::getAnchoredAt, null));
        CbamDeclaration result = getDeclaration(declaration.getId());
        outboxService.append(result, "DECLARATION_GENERATED");
        return result;
    }

    @Override
    public List<CbamDeclaration> listDeclarations(String enterpriseId, Integer year, String status) {
        LambdaQueryWrapper<CbamDeclaration> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(CbamDeclaration::getEnterpriseId, enterpriseId);
        }
        if (year != null) {
            wrapper.eq(CbamDeclaration::getReportYear, year);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(CbamDeclaration::getStatus, status);
        }
        wrapper.orderByDesc(CbamDeclaration::getReportYear).orderByDesc(CbamDeclaration::getId);
        List<CbamDeclaration> declarations = declarationMapper.selectList(wrapper);
        fillConsistency(declarations);
        return declarations;
    }

    @Override
    public CbamDeclaration getDeclaration(Long id) {
        CbamDeclaration declaration = requireDeclaration(id);
        fillConsistency(List.of(declaration));
        return declaration;
    }

    @Override
    public String exportDeclaration(Long id) {
        CbamDeclaration declaration = requireDeclaration(id);
        if (!StringUtils.hasText(declaration.getPackageJson())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申报包快照不存在，请重新生成");
        }
        return declaration.getPackageJson();
    }

    @Override
    @Transactional
    public CbamDeclaration submitDeclaration(Long id) {
        CbamDeclaration declaration = requireDeclaration(id);
        if (!"draft".equals(declaration.getStatus()) && !"returned".equals(declaration.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前状态不可提交");
        }
        if (!"complete".equals(declaration.getCompleteness())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "申报包存在缺失项，无法提交：" + declaration.getMissingItems());
        }
        declaration.setStatus("submitted");
        declaration.setReviewComment("");
        declaration.setUpdatedAt(LocalDateTime.now());
        declarationMapper.updateById(declaration);
        CbamDeclaration result = getDeclaration(id);
        outboxService.append(result, "DECLARATION_SUBMITTED");
        // 提交是冻结快照的时点：事务提交后把快照哈希与数据版本锚定上链
        anchorDeclaration(result);
        return result;
    }

    @Override
    @Transactional
    public CbamDeclaration reviewDeclaration(Long id, boolean approved, String comment) {
        CbamDeclaration declaration = requireDeclaration(id);
        String status = declaration.getStatus();
        if (!"submitted".equals(status) && !"approved".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅待确认或已确认的申报包可执行确认/退回");
        }
        if ("approved".equals(status) && approved) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申报包已确认，不能重复确认");
        }
        if (!approved && !StringUtils.hasText(comment)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "退回时必须填写意见");
        }
        declaration.setStatus(approved ? "approved" : "returned");
        declaration.setReviewComment(StringUtils.hasText(comment) ? comment.trim() : "");
        declaration.setUpdatedAt(LocalDateTime.now());
        declarationMapper.updateById(declaration);
        CbamDeclaration result = getDeclaration(id);
        outboxService.append(result, approved ? "DECLARATION_APPROVED" : "DECLARATION_RETURNED");
        // 兼容历史数据：若提交时未锚定（锚点功能上线前提交），确认时补写一次锚点
        if (approved) {
            anchorDeclaration(result);
        }
        return result;
    }

    /**
     * 事务提交后把申报包快照哈希与数据版本写入链上锚点。
     *
     * 锚点合约未配置、快照哈希缺失或已锚定时直接跳过；写链失败由 {@link ChainSyncService}
     * 按退避重试并最终生成 chain_failed 告警，不阻塞申报包状态流转。
     *
     * @param declaration 已提交/已确认的申报包（需含 snapshotHash 与 declarationNo）
     */
    private void anchorDeclaration(CbamDeclaration declaration) {
        if (declaration == null || !StringUtils.hasText(declaration.getSnapshotHash())
                || !StringUtils.hasText(declaration.getDeclarationNo())
                || StringUtils.hasText(declaration.getAnchorTxHash())) {
            return;
        }
        if (!chainGateway.cbamAnchorAvailable()) {
            log.info("CBAM 锚点合约未配置，申报包 {} 跳过锚点上链", declaration.getDeclarationNo());
            return;
        }
        Enterprise enterprise = enterpriseMapper.selectById(declaration.getEnterpriseId());
        String enterpriseName = enterprise == null ? "" : enterprise.getName();
        long version = declaration.getDataVersion() == null ? 0L : declaration.getDataVersion();
        chainSyncService.afterCommit("CBAM 申报包锚点", declaration.getEnterpriseId(), enterpriseName,
                String.valueOf(declaration.getReportYear()), () -> {
                    ChainAnchor anchor = chainGateway.anchorCbamDeclaration(declaration.getDeclarationNo(),
                            declaration.getReportYear(), version, declaration.getSnapshotHash());
                    CbamDeclaration patch = new CbamDeclaration();
                    patch.setId(declaration.getId());
                    patch.setAnchorTxHash(anchor.getTxHash());
                    patch.setAnchorBlock(anchor.getBlockNumber());
                    patch.setAnchoredAt(LocalDateTime.now());
                    declarationMapper.updateById(patch);
                });
    }

    @Override
    public Map<String, Object> stats(Integer year) {
        LambdaQueryWrapper<CbamDeclaration> wrapper = new LambdaQueryWrapper<>();
        if (year != null) {
            wrapper.eq(CbamDeclaration::getReportYear, year);
        }
        List<CbamDeclaration> declarations = declarationMapper.selectList(wrapper);
        fillConsistency(declarations);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("year", year);
        stats.put("declarations", declarations.size());
        stats.put("draft", countStatus(declarations, "draft"));
        stats.put("submitted", countStatus(declarations, "submitted"));
        stats.put("approved", countStatus(declarations, "approved"));
        stats.put("returned", countStatus(declarations, "returned"));
        stats.put("complete", declarations.stream().filter(d -> "complete".equals(d.getCompleteness())).count());
        stats.put("stale", declarations.stream().filter(d -> Boolean.TRUE.equals(d.getDataChanged())).count());
        stats.put("totalEmissions", declarations.stream()
                .map(d -> nz(d.getTotalEmissions())).reduce(BigDecimal.ZERO, BigDecimal::add));
        stats.put("emissionRecords", emissionMapper.selectCount(new LambdaQueryWrapper<CbamProductEmission>()
                .eq(year != null, CbamProductEmission::getReportYear, year)));
        return stats;
    }

    @Override
    @Transactional
    public Map<String, Object> reconcile() {
        List<CbamDeclaration> declarations = declarationMapper.selectList(
                new LambdaQueryWrapper<CbamDeclaration>()
                        .orderByDesc(CbamDeclaration::getReportYear)
                        .orderByDesc(CbamDeclaration::getId));
        fillConsistency(declarations);
        LocalDateTime now = LocalDateTime.now();
        int consistent = 0;
        int stale = 0;
        for (CbamDeclaration declaration : declarations) {
            boolean changed = Boolean.TRUE.equals(declaration.getDataChanged());
            CbamReconciliationLog log = new CbamReconciliationLog();
            log.setDeclarationId(declaration.getId());
            log.setDeclarationNo(declaration.getDeclarationNo());
            log.setEnterpriseId(declaration.getEnterpriseId());
            log.setReportYear(declaration.getReportYear());
            log.setSnapshotVersion(declaration.getDataVersion());
            log.setLiveVersion(declaration.getLiveVersion());
            log.setSnapshotTotal(declaration.getTotalEmissions());
            log.setLiveTotal(declaration.getLiveTotalEmissions());
            log.setStatus(changed ? "stale" : "consistent");
            log.setDetail(changed
                    ? String.format("版本 %s → %s；实时 %s / 快照 %s tCO2e",
                    declaration.getDataVersion(), declaration.getLiveVersion(),
                    declaration.getLiveTotalEmissions(), declaration.getTotalEmissions())
                    : String.format("版本 %s，实时与快照一致", declaration.getLiveVersion()));
            log.setCheckedAt(now);
            reconciliationLogMapper.insert(log);
            if (changed) {
                stale++;
            } else {
                consistent++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", now.toString());
        result.put("total", declarations.size());
        result.put("consistent", consistent);
        result.put("stale", stale);
        return result;
    }

    @Override
    public List<CbamReconciliationLog> listReconciliationLogs(Integer year, Long declarationId, Integer limit) {
        LambdaQueryWrapper<CbamReconciliationLog> wrapper = new LambdaQueryWrapper<>();
        if (year != null) {
            wrapper.eq(CbamReconciliationLog::getReportYear, year);
        }
        if (declarationId != null) {
            wrapper.eq(CbamReconciliationLog::getDeclarationId, declarationId);
        }
        wrapper.orderByDesc(CbamReconciliationLog::getCheckedAt).orderByDesc(CbamReconciliationLog::getId);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        wrapper.last("LIMIT " + size);
        return reconciliationLogMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public Map<String, Object> cleanupReconciliationLogs(Integer retentionDays) {
        int days = retentionDays == null ? 90 : retentionDays;
        if (days < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "保留天数必须大于 0");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int archived = reconciliationLogMapper.archiveBefore(cutoff);
        int deleted = reconciliationLogMapper.deleteBefore(cutoff);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionDays", days);
        result.put("archived", archived);
        result.put("deleted", deleted);
        return result;
    }

    // ==================== 内部工具 ====================

    /** 分组键：产品 + 装置。 */
    private record ItemKey(int goodsId, int installationId) {
    }

    /** 汇总器：同一产品 + 装置下的多条排放记录合并为申报明细。 */
    private static final class ItemAgg {
        private final int goodsId;
        private final int installationId;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal direct = BigDecimal.ZERO;
        private BigDecimal indirect = BigDecimal.ZERO;
        private BigDecimal carbonPrice = BigDecimal.ZERO;
        private boolean actualUsed;
        private final Set<String> methods = new LinkedHashSet<>();

        private ItemAgg(int goodsId, int installationId) {
            this.goodsId = goodsId;
            this.installationId = installationId;
        }

        private void add(CbamProductEmission emission) {
            quantity = quantity.add(nz(emission.getQuantity()));
            direct = direct.add(nz(emission.getDirectEmissions()));
            indirect = indirect.add(nz(emission.getIndirectEmissions()));
            carbonPrice = carbonPrice.add(nz(emission.getCarbonPrice()));
            methods.add(emission.getDataMethod());
            actualUsed = actualUsed || "actual".equals(emission.getDataMethod());
        }
    }

    private Map<Integer, CbamVerificationReport> verifiedReports(String enterpriseId, Integer year) {
        List<CbamVerificationReport> reports = reportMapper.selectList(new LambdaQueryWrapper<CbamVerificationReport>()
                .eq(CbamVerificationReport::getEnterpriseId, enterpriseId)
                .eq(CbamVerificationReport::getReportYear, year)
                .eq(CbamVerificationReport::getConclusion, "verified")
                .orderByDesc(CbamVerificationReport::getIssuedAt)
                .orderByDesc(CbamVerificationReport::getId));
        Map<Integer, CbamVerificationReport> map = new LinkedHashMap<>();
        for (CbamVerificationReport report : reports) {
            map.putIfAbsent(report.getInstallationId(), report);
        }
        return map;
    }

    private Map<String, Object> buildPackage(CbamDeclaration declaration, Enterprise enterprise,
                                             List<Map<String, Object>> items, Set<String> missing) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("schema", "carbon-trace-cbam-package/v1");
        pkg.put("generatedAt", LocalDateTime.now().toString());
        pkg.put("declarationNo", declaration.getDeclarationNo());
        pkg.put("year", declaration.getReportYear());
        Map<String, Object> declarant = new LinkedHashMap<>();
        declarant.put("enterpriseId", enterprise.getId());
        declarant.put("enterpriseName", enterprise.getName());
        declarant.put("creditCode", enterprise.getCreditCode());
        declarant.put("address", enterprise.getAddress());
        declarant.put("industry", enterprise.getIndustry());
        declarant.put("country", "CN");
        pkg.put("declarant", declarant);
        Map<String, Object> importer = new LinkedHashMap<>();
        importer.put("name", declaration.getImporterName());
        pkg.put("importer", importer);
        pkg.put("items", items);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("quantity", declaration.getTotalQuantity());
        totals.put("directEmissions", declaration.getDirectEmissions());
        totals.put("indirectEmissions", declaration.getIndirectEmissions());
        totals.put("totalEmissions", declaration.getTotalEmissions());
        totals.put("carbonPricePaid", declaration.getCarbonPrice());
        pkg.put("totals", totals);
        Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("status", missing.isEmpty() ? "complete" : "incomplete");
        completeness.put("missing", new ArrayList<>(missing));
        pkg.put("completeness", completeness);
        pkg.put("deadline", "CBAM 年度申报截止日为次年 9 月 30 日（2026 年度首次申报截止 2027-09-30）");
        pkg.put("note", "演示数据包：默认值为演示数值，正式申报需导入欧盟官方默认值与核查报告并按 CBAM Registry 格式转换");
        return pkg;
    }

    private Map<String, Object> installationBrief(CbamInstallation installation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", installation.getId());
        map.put("name", installation.getName());
        map.put("country", installation.getCountry());
        map.put("productionRoute", installation.getProductionRoute());
        return map;
    }

    private Map<String, Object> verificationBrief(CbamVerificationReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportNo", report.getReportNo());
        map.put("conclusion", report.getConclusion());
        map.put("issuedAt", report.getIssuedAt());
        CbamVerifier verifier = verifierMapper.selectById(report.getVerifierId());
        map.put("verifierName", verifier == null ? null : verifier.getName());
        map.put("accreditationNo", verifier == null ? null : verifier.getAccreditationNo());
        return map;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "申报包序列化失败");
        }
    }

    private long countStatus(List<CbamDeclaration> declarations, String status) {
        return declarations.stream().filter(d -> status.equals(d.getStatus())).count();
    }

    /**
     * 快照对账：按当前排放记录重算实时合计，标记申报包是否已落后于实时数据。
     * 申报包本身是冻结快照，这里只做“是否一致”的只读判断，不修改快照。
     */
    private void fillConsistency(List<CbamDeclaration> declarations) {
        for (CbamDeclaration declaration : declarations) {
            List<CbamProductEmission> emissions = emissionMapper.selectList(
                    new LambdaQueryWrapper<CbamProductEmission>()
                            .eq(CbamProductEmission::getEnterpriseId, declaration.getEnterpriseId())
                            .eq(CbamProductEmission::getReportYear, declaration.getReportYear()));
            BigDecimal live = emissions.stream()
                    .map(e -> nz(e.getDirectEmissions()).add(nz(e.getIndirectEmissions())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(3, RoundingMode.HALF_UP);
            long liveVersion = currentVersion(declaration.getEnterpriseId(), declaration.getReportYear());
            declaration.setLiveTotalEmissions(live);
            declaration.setLiveVersion(liveVersion);
            boolean versionChanged = declaration.getDataVersion() != null
                    && declaration.getDataVersion() != liveVersion;
            boolean totalsChanged = live.subtract(nz(declaration.getTotalEmissions())).abs()
                    .compareTo(new BigDecimal("0.001")) > 0;
            declaration.setDataChanged(versionChanged || totalsChanged);
        }
    }

    /** 读取企业 + 年度的当前数据版本；无记录返回 0。 */
    private long currentVersion(String enterpriseId, Integer year) {
        if (!StringUtils.hasText(enterpriseId) || year == null) {
            return 0L;
        }
        Long version = dataVersionMapper.selectVersion(enterpriseId, year);
        return version == null ? 0L : version;
    }

    /** 排放记录变更后原子自增版本；无记录时插入版本 1。 */
    private void bumpVersion(String enterpriseId, Integer year) {
        if (StringUtils.hasText(enterpriseId) && year != null) {
            dataVersionMapper.bumpVersion(enterpriseId, year);
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CbamGoods requireGoods(Integer id) {
        CbamGoods goods = id == null ? null : goodsMapper.selectById(id);
        if (goods == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CBAM 产品不存在");
        }
        return goods;
    }

    private CbamInstallation requireInstallation(Integer id) {
        CbamInstallation installation = id == null ? null : installationMapper.selectById(id);
        if (installation == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "生产装置不存在");
        }
        return installation;
    }

    private CbamProductEmission requireEmission(Long id) {
        CbamProductEmission emission = id == null ? null : emissionMapper.selectById(id);
        if (emission == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CBAM 排放记录不存在");
        }
        return emission;
    }

    private CbamDeclaration requireDeclaration(Long id) {
        CbamDeclaration declaration = id == null ? null : declarationMapper.selectById(id);
        if (declaration == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CBAM 申报包不存在");
        }
        return declaration;
    }

    private Enterprise requireEnterprise(String id) {
        Enterprise enterprise = StringUtils.hasText(id) ? enterpriseMapper.selectById(id) : null;
        if (enterprise == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "企业不存在：" + id);
        }
        return enterprise;
    }
}
