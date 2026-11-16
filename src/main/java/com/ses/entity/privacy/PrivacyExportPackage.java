package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DSAR の開示 package（{@code t_privacy_export_package}、V190。NF07-A2b、production-bridge-phased-plan.md §3.5.2）。
 *
 * <p>実体は {@code FileStorageService} の保存名（quarantine→published）で、汎用 file 経路からは取得させない
 * （{@code FileScopeValidationService} が tenant・role を問わず拒否する）。状態は DRAFT → APPROVED → PURGED の順に
 * 条件付き CAS で進め、{@code updateById} は使わない。論理削除列を持たない表のため論理削除の対象にしない。
 */
@Data
@TableName("t_privacy_export_package")
public class PrivacyExportPackage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long requestId;
    private String storedName;
    private String contentSha256;
    private Long sizeBytes;
    /** DRAFT / APPROVED / DELIVERED / PURGED。 */
    private String status;
    private LocalDateTime purgeAfter;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime purgedAt;
}
