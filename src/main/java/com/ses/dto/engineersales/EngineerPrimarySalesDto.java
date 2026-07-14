package com.ses.dto.engineersales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 要員の現任主担当営業（一覧画面の一括取得用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngineerPrimarySalesDto {

    /** 要員ID */
    private Long engineerId;

    /** 主担当営業ユーザーID */
    private Long salesUserId;

    /** 主担当営業氏名 */
    private String salesUserName;
}
