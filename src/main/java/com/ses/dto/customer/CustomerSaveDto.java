package com.ses.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerSaveDto {
    @NotBlank(message = "会社名は必須です")
    @Size(max = 100, message = "会社名は100文字以内で入力してください")
    private String companyName;

    @Size(max = 100, message = "フリガナは100文字以内で入力してください")
    private String companyNameKana;

    @Size(max = 50, message = "担当者名は50文字以内で入力してください")
    private String contactPerson;

    @Email(message = "メールアドレスの形式が正しくありません")
    @Size(max = 255, message = "メールアドレスは255文字以内で入力してください")
    private String contactEmail;

    @Size(max = 20, message = "電話番号は20文字以内で入力してください")
    private String contactPhone;

    @Size(max = 255, message = "住所は255文字以内で入力してください")
    private String address;

    @Size(max = 20, message = "商流位置は20文字以内で入力してください")
    private String commercialFlow;

    @Size(max = 1, message = "信頼度ランクは1文字以内で入力してください")
    private String trustLevel;

    @Size(max = 1000, message = "備考は1000文字以内で入力してください")
    private String remarks;
}
