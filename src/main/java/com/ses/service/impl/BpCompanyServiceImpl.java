package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.bpcompany.BpCompanyDto;
import com.ses.dto.bpcompany.BpTermsDto;
import com.ses.entity.BpBankAccount;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.entity.SysUser;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpTermsMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpTermsResolver;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BpCompanyServiceImpl extends ServiceImpl<BpCompanyMapper, BpCompany> implements BpCompanyService {

    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            "ACTIVE", Set.of("SUSPENDED", "INACTIVE"),
            "SUSPENDED", Set.of("ACTIVE", "INACTIVE"),
            "INACTIVE", Set.of("ACTIVE")
    );

    private final BpBankAccountMapper bankAccountMapper;
    private final BpTermsMapper termsMapper;
    private final BpTermsResolver termsResolver;
    private final SysUserMapper sysUserMapper;
    private final DataScopeService dataScopeService;

    @Value("${bp.bank-account-encryption-key:dev-only-change-this-bank-key}")
    private String bankAccountEncryptionKey;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @EventListener(ApplicationReadyEvent.class)
    void validateBankKeyConfig() {
        if (activeProfile.contains("prod") && bankAccountEncryptionKey.startsWith("dev-only")) {
            throw new IllegalStateException("bp.bank-account-encryption-key must be configured in prod");
        }
    }

    @Override
    public Page<BpCompanyDto> searchBpCompanies(String keyword, String entityType, String status, long current, long size) {
        Page<BpCompany> pageParam = new Page<>(current, size);
        LambdaQueryWrapper<BpCompany> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(BpCompany::getLegalName, keyword)
                    .or().like(BpCompany::getNameKana, keyword)
                    .or().like(BpCompany::getCorporateNumber, keyword)
                    .or().like(BpCompany::getInvoiceRegistrationNumber, keyword));
        }
        if (StringUtils.hasText(entityType)) {
            wrapper.eq(BpCompany::getEntityType, entityType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(BpCompany::getStatus, status);
        }
        applySalesDataScope(wrapper);
        wrapper.orderByDesc(BpCompany::getId);

        Page<BpCompany> pageResult = this.page(pageParam, wrapper);

        // Sales user map for name resolution
        List<Long> userIds = pageResult.getRecords().stream()
                .map(BpCompany::getPrimarySalesUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, String> userNameMap = userIds.isEmpty() ? Map.of() :
                sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, u -> u.getRealName() != null ? u.getRealName() : u.getUsername()));

        List<BpCompanyDto> dtoList = pageResult.getRecords().stream().map(c -> {
            BpCompanyDto dto = convertToDto(c);
            if (c.getPrimarySalesUserId() != null) {
                dto.setPrimarySalesUserName(userNameMap.get(c.getPrimarySalesUserId()));
            }
            return dto;
        }).collect(Collectors.toList());

        Page<BpCompanyDto> dtoPage = new Page<>(current, size, pageResult.getTotal());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    public BpCompanyDto getBpCompanyDetail(Long id) {
        BpCompany entity = this.getById(id);
        if (entity == null) {
            throw new BusinessException(404, "BP会社が見つかりません: " + id);
        }
        if (dataScopeService.isSalesDataScoped()) {
            Long currentUserId = SecurityUtils.currentUserId();
            if (currentUserId == null || !Objects.equals(entity.getPrimarySalesUserId(), currentUserId)) {
                throw new BusinessException(404, "BP会社が見つかりません: " + id);
            }
        }
        BpCompanyDto dto = convertToDto(entity);
        if (entity.getPrimarySalesUserId() != null) {
            SysUser user = sysUserMapper.selectById(entity.getPrimarySalesUserId());
            if (user != null) {
                dto.setPrimarySalesUserName(user.getRealName() != null ? user.getRealName() : user.getUsername());
            }
        }
        return dto;
    }

    @Override
    @Transactional
    public BpCompany createBpCompany(BpCompany bpCompany) {
        if (bpCompany.getStatus() == null) {
            bpCompany.setStatus("ACTIVE");
        }
        if (bpCompany.getTenantId() == null) {
            bpCompany.setTenantId(1L);
        }
        this.save(bpCompany);
        return bpCompany;
    }

    @Override
    @Transactional
    public BpCompany updateBpCompany(BpCompany bpCompany) {
        BpCompany existing = this.getById(bpCompany.getId());
        if (existing == null) {
            throw new BusinessException(404, "更新対象のBP会社が見つかりません");
        }
        if (bpCompany.getStatus() != null && !bpCompany.getStatus().equals(existing.getStatus())) {
            Set<String> allowedNext = ALLOWED_STATUS_TRANSITIONS.getOrDefault(existing.getStatus(), Set.of());
            if (!allowedNext.contains(bpCompany.getStatus())) {
                throw new BusinessException(400, "ステータス遷移が許可されていません: " + existing.getStatus() + " -> " + bpCompany.getStatus());
            }
        }
        applyNonNullFields(existing, bpCompany);
        boolean updated = this.updateById(existing);
        if (!updated) {
            throw new BusinessException(409, "他のユーザーによって更新されました。画面を再読み込みしてください。");
        }
        // 仮BPの正式化時は正規化名を外し、同名別法人の重複登録（R1.5警告・非block）を可能にする。
        if ("PROVISIONAL".equalsIgnoreCase(existing.getEntityType())
                && bpCompany.getEntityType() != null
                && !"PROVISIONAL".equalsIgnoreCase(bpCompany.getEntityType())) {
            this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpCompany>()
                    .eq(BpCompany::getId, existing.getId())
                    .set(BpCompany::getNormalizedName, null));
        }
        return this.getById(bpCompany.getId());
    }

    @Override
    @Transactional
    public void updateComplianceApplicability(Long id, String applicability, String note, Long operatorUserId) {
        BpCompany existing = this.getById(id);
        if (existing == null) {
            throw new BusinessException(404, "対象BP会社が見つかりません");
        }
        existing.setComplianceApplicability(applicability);
        existing.setApplicabilityNote(note);
        existing.setApplicabilityCheckedBy(operatorUserId);
        existing.setApplicabilityCheckedAt(LocalDateTime.now());
        this.updateById(existing);
    }

    @Override
    @Transactional
    public BpBankAccountDto addBankAccount(Long bpCompanyId, String bankName, String branchName, String accountType, String accountNumber, String accountHolder, LocalDate validFrom, LocalDate validTo) {
        if (!StringUtils.hasText(accountNumber)) {
            throw new BusinessException(400, "口座番号は必須です");
        }
        String lastFour = accountNumber.length() >= 4 ? accountNumber.substring(accountNumber.length() - 4) : "";
        String masked = "****" + lastFour;
        String encrypted = encryptAccountNumber(accountNumber);

        BpBankAccount bankAccount = BpBankAccount.builder()
                .tenantId(1L)
                .bpCompanyId(bpCompanyId)
                .bankName(bankName)
                .branchName(branchName)
                .accountType(accountType != null ? accountType : "ORDINARY")
                .encryptedAccountNumber(encrypted)
                .accountHolder(accountHolder)
                .maskedLabel(masked)
                .validFrom(validFrom != null ? validFrom : LocalDate.now())
                .validTo(validTo)
                .approvalStatus("PENDING")
                .build();

        bankAccountMapper.insert(bankAccount);
        return convertToBankDto(bankAccount);
    }

    @Override
    public List<BpBankAccountDto> getBankAccounts(Long bpCompanyId) {
        LambdaQueryWrapper<BpBankAccount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BpBankAccount::getBpCompanyId, bpCompanyId)
                .orderByDesc(BpBankAccount::getId);
        return bankAccountMapper.selectList(wrapper).stream()
                .map(this::convertToBankDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateBankAccountApproval(Long bankAccountId, String approvalStatus, Long operatorUserId) {
        String nextStatus = "APPROVED".equalsIgnoreCase(approvalStatus) ? "APPROVED" : "REJECTED";
        int updated = bankAccountMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpBankAccount>()
                        .eq(BpBankAccount::getId, bankAccountId)
                        .eq(BpBankAccount::getApprovalStatus, "PENDING")
                        .set(BpBankAccount::getApprovalStatus, nextStatus)
                        .set(BpBankAccount::getApprovedBy, operatorUserId)
                        .set(BpBankAccount::getApprovedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(409, "口座は申請中状態でないため承認/却下できません");
        }
    }

    @Override
    @Transactional
    public BpTerms addTerms(Long bpCompanyId, BpTerms terms) {
        terms.setTenantId(1L);
        terms.setBpCompanyId(bpCompanyId);
        if (terms.getEffectiveFrom() == null) {
            terms.setEffectiveFrom(LocalDate.now());
        }
        if (terms.getEffectiveTo() != null && terms.getEffectiveFrom().isAfter(terms.getEffectiveTo())) {
            throw new BusinessException(400, "条件の有効期間が不正です（開始日は終了日以前）");
        }
        LambdaQueryWrapper<BpTerms> overlapWrapper = new LambdaQueryWrapper<>();
        overlapWrapper.eq(BpTerms::getBpCompanyId, bpCompanyId)
                .le(BpTerms::getEffectiveFrom, terms.getEffectiveTo() != null ? terms.getEffectiveTo() : LocalDate.MAX)
                .and(w -> w.isNull(BpTerms::getEffectiveTo).or().ge(BpTerms::getEffectiveTo, terms.getEffectiveFrom()));
        if (termsMapper.selectCount(overlapWrapper) > 0) {
            throw new BusinessException(409, "同一BP会社に重複する有効期間の支払条件が存在します");
        }
        termsMapper.insert(terms);
        return terms;
    }

    @Override
    public BpTermsDto getActiveTermsAsOf(Long bpCompanyId, LocalDate targetDate) {
        BpTerms terms = termsResolver.resolveTermsAsOf(bpCompanyId, targetDate);
        if (terms == null) {
            return null;
        }
        return BpTermsDto.builder()
                .id(terms.getId())
                .bpCompanyId(terms.getBpCompanyId())
                .effectiveFrom(terms.getEffectiveFrom())
                .effectiveTo(terms.getEffectiveTo())
                .closingDay(terms.getClosingDay())
                .paymentMonthOffset(terms.getPaymentMonthOffset())
                .paymentDay(terms.getPaymentDay())
                .feeBearer(terms.getFeeBearer())
                .paymentMethod(terms.getPaymentMethod())
                .maxPaymentDays(terms.getMaxPaymentDays())
                .version(terms.getVersion())
                .build();
    }

    private void applySalesDataScope(LambdaQueryWrapper<BpCompany> wrapper) {
        if (!dataScopeService.isSalesDataScoped()) {
            return;
        }
        Long currentUserId = SecurityUtils.currentUserId();
        if (currentUserId == null) {
            // 許可集合が空のときはDB側で0件にする（空集合＝全件に化けない）。
            wrapper.eq(BpCompany::getId, -1L);
        } else {
            wrapper.eq(BpCompany::getPrimarySalesUserId, currentUserId);
        }
    }

    private void applyNonNullFields(BpCompany target, BpCompany source) {
        if (source.getLegalName() != null) target.setLegalName(source.getLegalName());
        if (source.getNameKana() != null) target.setNameKana(source.getNameKana());
        if (source.getEntityType() != null) target.setEntityType(source.getEntityType());
        if (source.getCorporateNumber() != null) target.setCorporateNumber(source.getCorporateNumber());
        if (source.getInvoiceRegistrationNumber() != null) target.setInvoiceRegistrationNumber(source.getInvoiceRegistrationNumber());
        if (source.getCapitalBand() != null) target.setCapitalBand(source.getCapitalBand());
        if (source.getEmployeeBand() != null) target.setEmployeeBand(source.getEmployeeBand());
        if (source.getAddress() != null) target.setAddress(source.getAddress());
        if (source.getRepresentative() != null) target.setRepresentative(source.getRepresentative());
        if (source.getStatus() != null) target.setStatus(source.getStatus());
        if (source.getSuspensionReason() != null) target.setSuspensionReason(source.getSuspensionReason());
        if (source.getSuspensionStartDate() != null) target.setSuspensionStartDate(source.getSuspensionStartDate());
        if (source.getSuspensionEndDate() != null) target.setSuspensionEndDate(source.getSuspensionEndDate());
        if (source.getSuspensionApprovedBy() != null) target.setSuspensionApprovedBy(source.getSuspensionApprovedBy());
        if (source.getRating() != null) target.setRating(source.getRating());
        if (source.getPrimarySalesUserId() != null) target.setPrimarySalesUserId(source.getPrimarySalesUserId());
    }

    private String encryptAccountNumber(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(
                    java.util.Arrays.copyOf(bankAccountEncryptionKey.getBytes(StandardCharsets.UTF_8), 32), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new BusinessException(500, "口座番号の暗号化に失敗しました");
        }
    }

    private BpCompanyDto convertToDto(BpCompany c) {
        return BpCompanyDto.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .legalName(c.getLegalName())
                .nameKana(c.getNameKana())
                .entityType(c.getEntityType())
                .corporateNumber(c.getCorporateNumber())
                .invoiceRegistrationNumber(c.getInvoiceRegistrationNumber())
                .capitalBand(c.getCapitalBand())
                .employeeBand(c.getEmployeeBand())
                .address(c.getAddress())
                .representative(c.getRepresentative())
                .status(c.getStatus())
                .rating(c.getRating())
                .primarySalesUserId(c.getPrimarySalesUserId())
                .complianceApplicability(c.getComplianceApplicability())
                .applicabilityCheckedBy(c.getApplicabilityCheckedBy())
                .applicabilityCheckedAt(c.getApplicabilityCheckedAt())
                .applicabilityNote(c.getApplicabilityNote())
                .version(c.getVersion())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private BpBankAccountDto convertToBankDto(BpBankAccount b) {
        return BpBankAccountDto.builder()
                .id(b.getId())
                .bpCompanyId(b.getBpCompanyId())
                .bankName(b.getBankName())
                .branchName(b.getBranchName())
                .accountType(b.getAccountType())
                .accountHolder(b.getAccountHolder())
                .maskedLabel(b.getMaskedLabel())
                .validFrom(b.getValidFrom())
                .validTo(b.getValidTo())
                .approvalStatus(b.getApprovalStatus())
                .approvedBy(b.getApprovedBy())
                .approvedAt(b.getApprovedAt())
                .build();
    }
}
