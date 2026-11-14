package com.ses.dto.asset;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingClearanceResultDto {

    /** 退社可否。返却・license条件と、失効要求を登録できないaccountの有無を照合する。 */
    private boolean clearancePassed;

    /** 未返却の有効貸与資産数 */
    private int unreturnedAssetCount;

    /** 未失効の有効外部アカウント数 */
    private int unrevokedAccountCount;

    /** 所有法人が不明なため失効要求を登録できない外部アカウント数 */
    private int unregisterableAccountCount;

    /** 未解放の有効ライセンス数 */
    private int unreleasedLicenseCount;

    /** 例外免除済みフラグ */
    private boolean waived;

    /** 未完了の残存項目メッセージリスト */
    private List<String> blockingItems;
}
