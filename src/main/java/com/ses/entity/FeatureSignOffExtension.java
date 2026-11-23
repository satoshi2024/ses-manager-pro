package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 証拠の類の署名の延長の記録（{@code t_feature_sign_off_extension}、V203。追記専用）。延長は署名の行の失効日を同じ行で更新し、
 * 同じ transaction でこの表に1行を足す（design §2.6.2）。更新・削除は {@link com.ses.mapper.FeatureSignOffExtensionMapper} と
 * DB の trigger が拒否する。
 */
@Data
@TableName("t_feature_sign_off_extension")
public class FeatureSignOffExtension {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long signOffId;

    /** 延長の申請（request_type = SIGN_OFF_EXTENSION）。 */
    private Long requestId;

    private LocalDate oldExpiresOn;

    private LocalDate newExpiresOn;

    private Long requestedBy;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;
}
