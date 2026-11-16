package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DSAR の検索・草案・手動対応の記録（{@code t_privacy_action}、V190）。
 *
 * <p>A2a は {@code MANUAL_ACTION} だけを書く: 範囲確定（SCOPED）の snapshot（{@code resultHash} = 範囲 snapshot の hash、
 * {@code createdAt} = snapshot の as-of）と、BLOCKED / CANCELLED の終了理由（{@code blockedReason}）。
 * {@code SEARCH} / {@code EXPORT_DRAFT} は A2b が書く。論理削除列を持たない表のため、論理削除の対象にしない。
 */
@Data
@TableName("t_privacy_action")
public class PrivacyAction implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long requestId;
    /** SEARCH / EXPORT_DRAFT / MANUAL_ACTION。 */
    private String actionType;
    private String providerId;
    private String dataElementId;
    /** PENDING / CLAIMED / SUCCEEDED / BLOCKED / UNKNOWN / FAILED。 */
    private String status;
    private String idempotencyKey;
    private String claimToken;
    private LocalDateTime leaseUntil;
    private Integer resultCount;
    private String resultHash;
    /** 拒否・終了の理由コード。 */
    private String blockedReason;
    private String evidenceRef;
    private Long rowVersion;
    private LocalDateTime createdAt;
}
