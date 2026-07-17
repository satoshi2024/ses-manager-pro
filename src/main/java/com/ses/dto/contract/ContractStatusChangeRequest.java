package com.ses.dto.contract;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/**
 * 契約ステータス変更リクエスト。
 * 共用の {@code dto.common.StatusChangeRequest} と異なり、解約遷移で必要となる
 * 解約日(cancelDate)を任意項目として持つ。解約以外の遷移では無視される。
 */
@Data
public class ContractStatusChangeRequest {
    @NotBlank(message = "ステータスは必須です")
    private String status;

    /** 解約日(実質終了日)。status='解約' のときのみ必須。 */
    private LocalDate cancelDate;
}
