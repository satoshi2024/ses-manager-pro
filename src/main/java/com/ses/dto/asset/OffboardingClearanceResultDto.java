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

    /** クリアランス判定（true: 退社可能 / 全返却・失効・免除済み, false: 退社ブロック） */
    private boolean clearancePassed;

    /** 未返却の有効貸与資産数 */
    private int unreturnedAssetCount;

    /** 未失効の有効外部アカウント数 */
    private int unrevokedAccountCount;

    /** 未解放の有効ライセンス数 */
    private int unreleasedLicenseCount;

    /** 例外免除済みフラグ */
    private boolean waived;

    /** 未完了の残存項目メッセージリスト */
    private List<String> blockingItems;
}
