package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 承認で変える設定（{@code t_feature_enablement_setting}、V203。現在は NF02 の公開範囲だけ）。申請者・承認者は承認した申請の行と
 * 複合 FK で一致する。値を未設定へ戻す更新は {@code UpdateWrapper.set(col, null)} で名指しする。
 */
@Data
@TableName("t_feature_enablement_setting")
public class FeatureEnablementSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String settingKey;

    /** 値（NULL は未設定）。 */
    private String settingValue;

    private Integer version;

    private Long requestId;

    private Long requestedBy;

    private Long approvedBy;

    private LocalDateTime approvedAt;
}
