package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * NF07 PII data element catalog（{@code m_pii_data_element}、V190）。構造の行だけを持ち、保持期間は持たない。
 *
 * <p>行は F2 の seed が入れる。{@code subjectAttribution} の既定は {@code UNKNOWN} で、A1H は UNKNOWN を
 * fail-closed として扱う（C-2。値の解釈は {@code SubjectAttribution#fromStored}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_pii_data_element")
public class PiiDataElement extends BaseEntity {
    private String tenantId;
    private String dataElementId;
    private String storageKind;
    private String storageRef;
    private String dataClass;
    private String ownerState;
    private String ownerRef;
    /** F2 の provider。NULL は未登録（検索・処分の対象外として報告する）。 */
    private String providerId;
    private String subjectAttribution;
    private Integer thirdPartyFlag;
    private String inventoryStatus;
    private String inventoryRef;
}
