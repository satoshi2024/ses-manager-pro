package com.ses.dto.portal;

import com.ses.entity.PortalSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * portal管理向けsession一覧DTO。tokenHash / ipHash は含めない（S13-P1-01）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalSessionAdminDto {
    private Long id;
    private LocalDateTime issuedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime expiresAt;
    private String userAgent;

    public static PortalSessionAdminDto from(PortalSession session) {
        if (session == null) {
            return null;
        }
        return PortalSessionAdminDto.builder()
                .id(session.getId())
                .issuedAt(session.getIssuedAt())
                .lastSeenAt(session.getLastSeenAt())
                .expiresAt(session.getExpiresAt())
                .userAgent(session.getUserAgent())
                .build();
    }
}
