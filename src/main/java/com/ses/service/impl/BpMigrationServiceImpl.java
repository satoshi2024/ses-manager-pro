package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.bpmigration.BpMigrationExceptionDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpCompany;
import com.ses.entity.BpPayment;
import com.ses.entity.BpTerms;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.BpMigrationService;
import com.ses.service.BpTermsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BpMigrationServiceImpl implements BpMigrationService {

    private final BpAvailabilityMapper availabilityMapper;
    private final BpPaymentMapper paymentMapper;
    private final BpCompanyMapper bpCompanyMapper;
    private final BpTermsResolver termsResolver;

    @Override
    public String normalizeName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return "";
        }
        // 全角英数・記号の半角化 (NFKC)
        String normalized = Normalizer.normalize(rawName, Normalizer.Form.NFKC);
        // 大文字変換
        normalized = normalized.toUpperCase();
        // 法人格記号の削除・統一
        normalized = normalized.replaceAll("株式会社|\\(株\\)|（株）", "")
                .replaceAll("合同会社|\\(同\\)|（同）", "")
                .replaceAll("有限会社|\\(有\\)|（有）", "")
                .replaceAll("\\s+", "");
        return normalized;
    }

    @Override
    @Transactional
    public void migrateLegacyBpData() {
        // 1. BpAvailability 移行
        LambdaQueryWrapper<BpAvailability> availWrapper = new LambdaQueryWrapper<>();
        availWrapper.isNull(BpAvailability::getBpCompanyId)
                .isNotNull(BpAvailability::getBpCompany);
        List<BpAvailability> unmigratedAvail = availabilityMapper.selectList(availWrapper);

        for (BpAvailability avail : unmigratedAvail) {
            String rawName = avail.getBpCompany();
            if (!StringUtils.hasText(rawName)) {
                continue;
            }
            BpCompany company = findOrCreateProvisionalCompany(rawName);
            avail.setBpCompanyId(company.getId());
            availabilityMapper.updateById(avail);
        }

        // 2. BpPayment 移行
        LambdaQueryWrapper<BpPayment> payWrapper = new LambdaQueryWrapper<>();
        payWrapper.isNull(BpPayment::getBpCompanyId)
                .isNotNull(BpPayment::getPayeeCompanyName);
        List<BpPayment> unmigratedPayments = paymentMapper.selectList(payWrapper);

        for (BpPayment pay : unmigratedPayments) {
            String rawName = pay.getPayeeCompanyName();
            if (!StringUtils.hasText(rawName)) {
                continue;
            }
            BpCompany company = findOrCreateProvisionalCompany(rawName);
            pay.setBpCompanyId(company.getId());

            // スナップショットが未設定なら過去表示保護のために設定
            if (!StringUtils.hasText(pay.getBpCompanyNameSnapshot())) {
                pay.setBpCompanyNameSnapshot(rawName);
            }

            if (!StringUtils.hasText(pay.getTermsSnapshotJson())) {
                LocalDate targetDate = pay.getPaidDate() != null ? pay.getPaidDate() : LocalDate.now();
                BpTerms activeTerms = termsResolver.resolveTermsAsOf(company.getId(), targetDate);
                if (activeTerms != null) {
                    pay.setTermsSnapshotJson(buildTermsJson(activeTerms));
                }
            }
            paymentMapper.updateById(pay);
        }
    }

    @Override
    public List<BpMigrationExceptionDto> getMigrationExceptions() {
        List<BpMigrationExceptionDto> exceptions = new ArrayList<>();

        // 空名または未解決データ
        LambdaQueryWrapper<BpAvailability> availWrapper = new LambdaQueryWrapper<>();
        availWrapper.isNull(BpAvailability::getBpCompanyId);
        availabilityMapper.selectList(availWrapper).forEach(a -> {
            exceptions.add(BpMigrationExceptionDto.builder()
                    .sourceType("AVAILABILITY")
                    .sourceId(a.getId())
                    .rawCompanyName(a.getBpCompany())
                    .normalizedCompanyName(normalizeName(a.getBpCompany()))
                    .reason("UNASSIGNED_BP_ID")
                    .build());
        });

        LambdaQueryWrapper<BpPayment> payWrapper = new LambdaQueryWrapper<>();
        payWrapper.isNull(BpPayment::getBpCompanyId);
        paymentMapper.selectList(payWrapper).forEach(p -> {
            exceptions.add(BpMigrationExceptionDto.builder()
                    .sourceType("PAYMENT")
                    .sourceId(p.getId())
                    .rawCompanyName(p.getPayeeCompanyName())
                    .normalizedCompanyName(normalizeName(p.getPayeeCompanyName()))
                    .reason("UNASSIGNED_BP_ID")
                    .build());
        });

        return exceptions;
    }

    @Override
    @Transactional
    public void resolveMigrationException(String sourceType, Long sourceId, Long bpCompanyId) {
        BpCompany company = bpCompanyMapper.selectById(bpCompanyId);
        if (company == null) {
            throw new BusinessException(404, "指定されたBP会社が存在しません");
        }

        if ("AVAILABILITY".equalsIgnoreCase(sourceType)) {
            BpAvailability avail = availabilityMapper.selectById(sourceId);
            if (avail != null) {
                avail.setBpCompanyId(bpCompanyId);
                availabilityMapper.updateById(avail);
            }
        } else if ("PAYMENT".equalsIgnoreCase(sourceType)) {
            BpPayment pay = paymentMapper.selectById(sourceId);
            if (pay != null) {
                pay.setBpCompanyId(bpCompanyId);
                if (!StringUtils.hasText(pay.getBpCompanyNameSnapshot())) {
                    pay.setBpCompanyNameSnapshot(pay.getPayeeCompanyName());
                }
                paymentMapper.updateById(pay);
            }
        }
    }

    private BpCompany findOrCreateProvisionalCompany(String rawName) {
        String norm = normalizeName(rawName);
        LambdaQueryWrapper<BpCompany> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BpCompany::getLegalName, rawName);
        BpCompany exactMatch = bpCompanyMapper.selectOne(wrapper);
        if (exactMatch != null) {
            return exactMatch;
        }

        // 仮BP生成
        BpCompany provisional = BpCompany.builder()
                .tenantId(1L)
                .legalName(rawName)
                .nameKana(norm)
                .entityType("PROVISIONAL")
                .status("ACTIVE")
                .build();
        bpCompanyMapper.insert(provisional);
        return provisional;
    }

    private String buildTermsJson(BpTerms terms) {
        return String.format("{\"closingDay\":%d,\"paymentMonthOffset\":%d,\"paymentDay\":%d,\"feeBearer\":\"%s\",\"maxPaymentDays\":%d}",
                terms.getClosingDay(), terms.getPaymentMonthOffset(), terms.getPaymentDay(), terms.getFeeBearer(), terms.getMaxPaymentDays());
    }
}
