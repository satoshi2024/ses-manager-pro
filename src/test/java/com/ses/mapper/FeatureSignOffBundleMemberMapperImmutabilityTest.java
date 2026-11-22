package com.ses.mapper;

import com.ses.entity.FeatureSignOffBundleMember;
import org.junit.jupiter.api.Test;

/**
 * FE-A02: 署名の組の member（{@code t_feature_sign_off_bundle_member}、V203。追記専用）の mapper は、{@code BaseMapper} の全ての
 * 更新・削除を SQL の発行前に拒否する。取り消した member を替えるには新しい組を登録する（design §2.6.1）。DB の trigger の拒否は
 * {@code FeatureEnablementSchemaMySqlTest}（mysql shard 1）が確かめる。
 */
class FeatureSignOffBundleMemberMapperImmutabilityTest {

    @Test
    void 組のmemberのmapperは全ての更新と削除を拒否し追記と読取は使える() {
        AppendOnlyMapperContract.assertAppendOnly(FeatureSignOffBundleMemberMapper.class,
                FeatureSignOffBundleMember.class, "t_feature_sign_off_bundle_member");
    }

    @Test
    void 組のmemberのentityを一括の更新経路で包まない() throws Exception {
        AppendOnlyMapperContract.assertNotWrapped(FeatureSignOffBundleMember.class);
    }
}
