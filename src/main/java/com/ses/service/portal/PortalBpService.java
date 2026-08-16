package com.ses.service.portal;

import com.ses.dto.portal.PortalBpAvailabilityDto;
import com.ses.dto.portal.PortalBpAvailabilityRequest;
import com.ses.dto.portal.PortalBpBankAccountRequest;
import com.ses.dto.portal.PortalBpPaymentDto;
import com.ses.dto.portal.PortalBpSubmissionDto;

import java.io.InputStream;
import java.util.List;

/**
 * BPポータルサービス（R3）。全メソッドは expectedBpCompanyId（portal orgのbp_company_id）を
 * SQL境界条件として使い、自社分のみを返す（design §6.2・R4.3）。
 * 金額・支払状態を変更するAPIは存在させない（R3.3）。
 */
public interface PortalBpService {

    // ===== 空き要員（R3.1。登録→内部営業review→有効化） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalBpAvailabilityDto> availabilities(
            long current, long size, Long bpCompanyId);

    PortalBpAvailabilityDto createAvailability(Long bpCompanyId, PortalBpAvailabilityRequest request);

    PortalBpAvailabilityDto updateAvailability(Long availabilityId, Long bpCompanyId,
                                               PortalBpAvailabilityRequest request);

    /** 停止（自社の提案可能行のみ。status→失効） */
    void stopAvailability(Long availabilityId, Long bpCompanyId);

    // ===== 発注・作業実績（R3.2。受領確認のみ変更可） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalBpPaymentDto> payments(
            long current, long size, Long bpCompanyId, String status);

    /** 受領確認の一回性CAS（R3.2）。 */
    void confirmReceipt(Long paymentId, Long bpCompanyId);

    // ===== 請求書/作業報告書の提出（R3.2。archive quarantine/scan通過後のみ公開: R4.4） =====
    PortalBpSubmissionDto submitDocument(Long paymentId, Long bpCompanyId, String originalName,
                                         String contentType, byte[] content);

    List<PortalBpSubmissionDto> submissions(Long paymentId, Long bpCompanyId);

    InputStream downloadSubmission(Long documentId, Long paymentId, Long bpCompanyId);

    // ===== 支払状況（R3.3。参照のみ） =====
    PortalBpPaymentDto payment(Long paymentId, Long bpCompanyId);

    // ===== 口座変更申請（R3.4。承認前は支払先へ反映されない） =====
    List<com.ses.dto.bpcompany.BpBankAccountDto> bankAccounts(Long bpCompanyId);

    void requestBankAccountChange(Long bpCompanyId, PortalBpBankAccountRequest request);
}
