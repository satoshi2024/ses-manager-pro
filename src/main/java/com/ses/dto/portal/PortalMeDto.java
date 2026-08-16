package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * portalログイン後ユーザー情報（自情報のみ。内部entityをJSON化しない）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalMeDto {
    private String email;
    private String displayName;
    private String orgType;
    private boolean orgAdmin;
    private boolean termsPending;
    /** email通知設定（R4.1） */
    private Boolean notifyEmail;
    private Set<String> permissions;
}
