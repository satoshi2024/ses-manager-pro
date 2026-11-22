package com.ses.mapper;

import com.ses.entity.FeatureSignOffExtension;
import org.junit.jupiter.api.Test;

/**
 * FE-A02（spec の micro review 88630af1 の P3-M3）: 署名の延長の記録（{@code t_feature_sign_off_extension}、V203。追記専用）の mapper は、
 * {@code BaseMapper} の全ての更新・削除を SQL の発行前に拒否する。延長は署名の行の失効日を更新し、この表には1行を足すだけ
 * （design §2.6.2）。DB の trigger の拒否は {@code FeatureEnablementSchemaMySqlTest}（mysql shard 1）が確かめる。
 */
class FeatureSignOffExtensionMapperImmutabilityTest {

    @Test
    void 延長の記録のmapperは全ての更新と削除を拒否し追記と読取は使える() {
        AppendOnlyMapperContract.assertAppendOnly(FeatureSignOffExtensionMapper.class, FeatureSignOffExtension.class,
                "t_feature_sign_off_extension");
    }

    @Test
    void 延長の記録のentityを一括の更新経路で包まない() throws Exception {
        AppendOnlyMapperContract.assertNotWrapped(FeatureSignOffExtension.class);
    }
}
