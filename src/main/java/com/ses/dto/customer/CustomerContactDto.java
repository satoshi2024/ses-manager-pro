package com.ses.dto.customer;

import lombok.Data;

import java.time.LocalDate;

/** 画面・CSVへ公開する顧客担当者DTO。内部entityを直接公開しない。 */
@Data
public class CustomerContactDto {
    private Long id;
    private Long customerId;
    private String name;
    private String nameKana;
    private String department;
    private String position;
    private String rolesJson;
    private String email;
    private String phone;
    private Integer primaryFlag;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;
    private Integer version;
}
