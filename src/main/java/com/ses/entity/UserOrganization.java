package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** ユーザーの組織所属履歴エンティティ。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_organization")
public class UserOrganization extends BaseEntity {

    private Long userId;
    private Long organizationId;
    private String positionName;
    private Long managerUserId;
    private Integer primaryFlag;
    private LocalDate validFrom;
    private LocalDate validTo;

    @Version
    private Integer version;
}
