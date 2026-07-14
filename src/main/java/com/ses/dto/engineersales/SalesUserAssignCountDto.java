package com.ses.dto.engineersales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 営業ユーザー別の現任主担当要員数（営業成績集計用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesUserAssignCountDto {

    /** 営業ユーザーID */
    private Long salesUserId;

    /** 現任主担当としての要員数 */
    private Long engineerCount;
}
