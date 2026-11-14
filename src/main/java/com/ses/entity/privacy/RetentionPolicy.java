package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NF07 retention policy（{@code m_retention_policy}、V190）。署名 envelope と current pointer を持つ。
 *
 * <p>論理削除・更新日時を持たない（policy は revision を積み、状態だけを遷移させる）。第1期の application は
 * 本表を読むだけで、書き込みは署名後の NF07-REG が行う。法的な値は envelope の canonical bytes にだけ入る。
 */
@Data
@TableName("m_retention_policy")
public class RetentionPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String policyId;
    private String policyRevision;
    private String moduleCode;
    /** NULL = tenant-wide（P-LE 未決のため法人単位の主張はしない）。 */
    private Long legalEntityId;
    private String dataElementId;
    private String targetAction;
    private String targetProvider;
    /** CANDIDATE / CURRENT / SUPERSEDED / REVOKED。登録状態であって承認の証明ではない。 */
    private String status;

    /** current pointer（MySQLの STORED 生成列）。生成列へ書き込むとDBが拒否するため insert/update しない。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String currentKey;

    private Integer envelopeSchemaVersion;
    private byte[] envelopeCanonical;
    private byte[] envelopeSignature;
    private String envelopeDigest;
    private String authorityId;
    private String keyId;
    private Long registeredBy;
    private LocalDateTime registeredAt;
    private Long activatedBy;
    private LocalDateTime activatedAt;
    private Long rowVersion;
}
