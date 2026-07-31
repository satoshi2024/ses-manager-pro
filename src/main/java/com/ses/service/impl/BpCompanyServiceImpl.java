package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BpCompanyServiceImpl extends ServiceImpl<BpCompanyMapper, BpCompany> implements BpCompanyService {

    private final BpBankAccountMapper bankAccountMapper;
    private final BpTermsMapper termsMapper;
    private final BpTermsResolver termsResolver;
    private final SysUserMapper sysUserMapper;

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
        this.updateById(bpCompany);
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
    public BpBankAccount addBankAccount(Long bpCompanyId, String bankName, String branchName, String accountType, String accountNumber, String accountHolder, LocalDate validFrom, LocalDate validTo) {
        if (!StringUtils.hasText(accountNumber)) {
            throw new BusinessException(400, "口座番号は必須です");
        }
        String masked = "****" + (accountNumber.length() >= 4 ? accountNumber.substring(accountNumber.length() - 4) : accountNumber);
        String encrypted = Base64.getEncoder().encodeToString(accountNumber.getBytes(StandardCharsets.UTF_8));

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
        return bankAccount;
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
    public void approveBankAccount(Long bankAccountId, Long operatorUserId) {
        BpBankAccount account = bankAccountMapper.selectById(bankAccountId);
        if (account == null) {
            throw new BusinessException(404, "口座情報が見つかりません");
        }
        account.setApprovalStatus("APPROVED");
        account.setApprovedBy(operatorUserId);
        account.setApprovedAt(LocalDateTime.now());
        bankAccountMapper.updateById(account);
    }

    @Override
    @Transactional
    public BpTerms addTerms(Long bpCompanyId, BpTerms terms) {
        terms.setTenantId(1L);
        terms.setBpCompanyId(bpCompanyId);
        if (terms.getEffectiveFrom() == null) {
            terms.setEffectiveFrom(LocalDate.now());
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
