package com.ses.dto.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * BP portalの口座変更申請リクエスト（R3.4。承認前は支払先へ反映されない）。
 * 口座番号は暗号化して保存され、ログ・API応答へは出ない。
 */
@Data
public class PortalBpBankAccountRequest {

    @NotBlank(message = "error.portal.bp.bankNameRequired")
    @Size(max = 200, message = "error.portal.bp.bankNameTooLong")
    private String bankName;

    @NotBlank(message = "error.portal.bp.branchNameRequired")
    @Size(max = 200, message = "error.portal.bp.branchNameTooLong")
    private String branchName;

    @NotBlank(message = "error.portal.bp.accountTypeRequired")
    private String accountType;

    @NotBlank(message = "error.portal.bp.accountNumberRequired")
    @Size(max = 50, message = "error.portal.bp.accountNumberTooLong")
    private String accountNumber;

    @NotBlank(message = "error.portal.bp.accountHolderRequired")
    @Size(max = 200, message = "error.portal.bp.accountHolderTooLong")
    private String accountHolder;
}
