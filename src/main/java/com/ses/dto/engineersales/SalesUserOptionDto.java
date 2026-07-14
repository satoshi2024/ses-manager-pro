package com.ses.dto.engineersales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 営業ユーザー選択肢DTO（担当営業セレクトボックス用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesUserOptionDto {

    /** ユーザーID */
    private Long id;

    /** 氏名 */
    private String realName;

    /** ログインID */
    private String username;
}
