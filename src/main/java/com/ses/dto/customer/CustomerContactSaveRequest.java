package com.ses.dto.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 顧客担当者の登録・更新リクエスト。 */
@Data
public class CustomerContactSaveRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String nameKana;

    @Size(max = 100)
    private String department;

    @Size(max = 100)
    private String position;

    @Size(max = 1000)
    private String rolesJson;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    private Integer primaryFlag;

    @NotNull
    private LocalDate validFrom;

    private LocalDate validTo;

    @NotBlank
    @Size(max = 20)
    private String status;

    private Integer version;
}
