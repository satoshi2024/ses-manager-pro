package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 要員プロフィール/スキル変更申請（t_engineer_change_request）。
 * 状態機械: 下書き→申請中→承認済→反映済 / 取下げ（design §6.3）。
 * applied_at IS NULL = 未反映（承認済でも反映前がありうる。design §6.1）。
 * payload_json は request_type ごとのDTO allowlist を通過した値のみを保存する（任意JSON→entity反映の禁止）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_engineer_change_request")
public class EngineerChangeRequest extends BaseEntity {

    /** 申請元要員ID */
    private Long engineerId;

    /** profile.change / skill.change / career.change */
    private String requestType;

    /** 申請内容（type別DTOのallowlistのみ。JSON文字列） */
    private String payloadJson;

    /** before/after diff（JSON文字列） */
    private String diffJson;

    /** 下書き/申請中/承認済/反映済/取下げ */
    private String status;

    /** 承認ワークフロー申請ID（approval engine連携） */
    private Long approvalRequestId;

    /** master反映日時。NULL=未反映 */
    private LocalDateTime appliedAt;

    @Version
    private Integer version;
}
