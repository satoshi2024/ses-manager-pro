package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_recommendation_run")
public class AiRecommendationRun extends BaseEntity {

    private String traceId;
    private String useCase;
    private Long artifactVersionId;
    private Long actorUserId;
    private String inputHash;
    private String redactedSummaryJson;
    private Integer latencyMs;
    private Integer tokenInput;
    private Integer tokenOutput;
    private Integer costJpy;
    private String status;
    private Integer statusVersion;
    private String errorCode;

    /** query開始時点の認可snapshot。legacy matchingも同じ境界を保存する。 */
    private String scopeHash;
    private String parameterHash;
    private String tenantId;
    private Long legalEntityId;
    private LocalDateTime asOfAt;
    private String timezoneId;
    private String catalogVersion;
    private String dataVersion;

    /*
     * NF08 B2c-1（V194）: summary dispatchのdurable claimと発行時binding。
     * 汎用のinsert / updateByIdでは書かない（insertStrategy・updateStrategy = NEVER）。既存行はDB既定値の
     * NOT_APPLICABLE / NULLのままで、READY化・claimは CopilotDispatchClaimService が列を明示した
     * AiRecommendationRunMapper のSQLだけで行う。古いentityをupdateByIdで書き戻してもclaim状態を巻き戻せない。
     * BINARY列はbyte[]で読み、dispatch_claim_id の表記変換はmapperの UUID_TO_BIN / BIN_TO_UUID(?, 0) に限る。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchState;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchClaimId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchNonceHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchIdempotencyHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchBindingHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] preflightSnapshotHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchRequestSha256;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime dispatchIssuedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime dispatchExpiresAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime dispatchClaimedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime dispatchStartedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchProvider;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchModelRevision;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchRegion;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchTier;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchConfigHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long dispatchArtifactVersionId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchScopeHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchInputHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchApprovalDigest;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchCardId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchCardVersion;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchCardDigest;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchPolicyId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String dispatchPolicyVersion;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private byte[] dispatchPolicyDigest;
}
