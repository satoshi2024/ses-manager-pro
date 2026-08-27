package com.ses.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

/**
 * ライフサイクル案件起票コマンド
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLifecycleCaseCommand {

    /**
     * 対象要員ID
     */
    @NotNull(message = "対象要員は必須です")
    private Long engineerId;

    /**
     * ライフサイクル種別 (JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION)
     */
    @NotBlank(message = "ライフサイクル種別は必須です")
    private String lifecycleType;

    /**
     * 適用テンプレートID (指定なき場合は種別と基準日から有効版を自動解決)
     */
    private Long templateId;

    /**
     * 基準日 (入社日、異動日、退社日等)
     */
    @NotNull(message = "基準日は必須です")
    private LocalDate anchorDate;

    /**
     * 案件タイトル
     */
    private String title;

    /**
     * 特記事項・備考
     */
    private String remarks;

    /**
     * 個別タスク担当者指定 (タスクコード -> ユーザーID / 任意)
     */
    private Map<String, Long> customAssignees;
}
