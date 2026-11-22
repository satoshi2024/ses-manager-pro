package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 署名の組と署名の対応（{@code t_feature_sign_off_bundle_member}、V203。追記専用）。主キーは（組・種別）の複合 key のため
 * {@code @TableId} を持たない。member の種別・tenant は署名の行と複合 FK で一致する。引き継いだ署名は旧い組と新しい組の両方に属する。
 * 更新・削除は {@link com.ses.mapper.FeatureSignOffBundleMemberMapper} と DB の trigger が拒否する。
 */
@Data
@TableName("t_feature_sign_off_bundle_member")
public class FeatureSignOffBundleMember {

    private Long bundleId;

    private String tenantId;

    private String kindCode;

    private Long signOffId;

    /** 1: 旧い組から引き継いだ確認済みの署名。 */
    private Integer carriedOver;
}
