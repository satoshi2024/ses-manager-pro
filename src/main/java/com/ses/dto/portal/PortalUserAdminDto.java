package com.ses.dto.portal;

import com.ses.entity.PortalUser;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * portal管理向けuser一覧DTO。passwordHash / TOTP secret / recovery hash は含めない（S13-P0-01）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalUserAdminDto {
    private Long id;
    private Long portalOrgId;
    private String email;
    private String displayName;
    private String status;
    private String mfaPolicy;
    private Integer notifyEmail;
    private LocalDateTime mfaEnabledAt;
    private LocalDateTime lastLoginAt;

    public static PortalUserAdminDto from(PortalUser user) {
        if (user == null) {
            return null;
        }
        return PortalUserAdminDto.builder()
                .id(user.getId())
                .portalOrgId(user.getPortalOrgId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .status(user.getStatus())
                .mfaPolicy(user.getMfaPolicy())
                .notifyEmail(user.getNotifyEmail())
                .mfaEnabledAt(user.getMfaEnabledAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }
}
