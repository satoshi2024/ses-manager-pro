package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

/**
 * 顧客担当者エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_contact")
public class CustomerContact extends BaseEntity {

    /**
     * 顧客ID
     */
    @NotNull(message = "顧客IDは必須です")
    private Long customerId;

    /**
     * 担当者名
     */
    @NotBlank(message = "担当者名は必須です")
    private String name;

    /**
     * 担当者名カナ
     */
    private String nameKana;

    /**
     * 部署
     */
    private String department;

    /**
     * 役職
     */
    private String position;

    /**
     * 役割(JSON配列: 決裁者/現場/調達/請求/契約)
     */
    private String rolesJson;

    /**
     * メールアドレス
     */
    @Email(message = "メールアドレスの形式が正しくありません")
    private String email;

    /**
     * 電話番号
     */
    private String phone;

    /**
     * 主担当フラグ(1:主担当)
     */
    private Integer primaryFlag;

    /**
     * 有効開始日(inclusive)
     */
    @NotNull(message = "有効開始日は必須です")
    private LocalDate validFrom;

    /**
     * 有効終了日(inclusive, NULL=無期限)
     */
    private LocalDate validTo;

    /**
     * ステータス(有効/退職/異動)
     */
    @NotBlank(message = "ステータスは必須です")
    private String status;

    /**
     * 楽観ロックバージョン
     */
    @Version
    private Integer version;
}
