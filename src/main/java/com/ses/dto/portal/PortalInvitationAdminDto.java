package com.ses.dto.portal;

import com.ses.entity.PortalInvitation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * portal管理向け招待DTO。tokenHash は含めない（S13-P2-03）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalInvitationAdminDto {
    private Long id;
    private Long portalOrgId;
    private String email;
    private String role;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private Long acceptedBy;
    private Long invitedBy;

    public static PortalInvitationAdminDto from(PortalInvitation invitation) {
        if (invitation == null) {
            return null;
        }
        return PortalInvitationAdminDto.builder()
                .id(invitation.getId())
                .portalOrgId(invitation.getPortalOrgId())
                .email(invitation.getEmail())
                .role(invitation.getRole())
                .expiresAt(invitation.getExpiresAt())
                .usedAt(invitation.getUsedAt())
                .acceptedBy(invitation.getAcceptedBy())
                .invitedBy(invitation.getInvitedBy())
                .build();
    }
}
