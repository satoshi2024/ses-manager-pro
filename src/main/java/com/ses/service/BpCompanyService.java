package com.ses.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.bpcompany.BpCompanyDto;
import com.ses.dto.bpcompany.BpTermsDto;
import com.ses.entity.BpBankAccount;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;

import java.time.LocalDate;
import java.util.List;

public interface BpCompanyService extends IService<BpCompany> {

    Page<BpCompanyDto> searchBpCompanies(String keyword, String entityType, String status, long current, long size);

    BpCompanyDto getBpCompanyDetail(Long id);

    BpCompany createBpCompany(BpCompany bpCompany);

    BpCompany updateBpCompany(BpCompany bpCompany);

    void updateComplianceApplicability(Long id, String applicability, String note, Long operatorUserId);

    // 口座管理
    BpBankAccountDto addBankAccount(Long bpCompanyId, String bankName, String branchName, String accountType, String accountNumber, String accountHolder, LocalDate validFrom, LocalDate validTo);

    List<BpBankAccountDto> getBankAccounts(Long bpCompanyId);

    /** 口座申請の承認/却下。PENDING以外からの遷移は状態CASで拒否する。 */
    void updateBankAccountApproval(Long bankAccountId, String approvalStatus, Long operatorUserId);

    // 条件管理
    BpTerms addTerms(Long bpCompanyId, BpTerms terms);

    BpTermsDto getActiveTermsAsOf(Long bpCompanyId, LocalDate targetDate);
}
