package com.ses.mapper;

import com.ses.entity.FeatureEnablementEvent;
import org.junit.jupiter.api.Test;

/**
 * FE-A02: 機能の開放の履歴（{@code t_feature_enablement_event}、V203。追記専用）の mapper は、{@code BaseMapper} の全ての更新・削除を
 * SQL の発行前に拒否する（H2 の replay には trigger を置かないため、fast test では mapper 層の拒否が唯一の防御。CLAUDE.md の Layering）。
 * DB の trigger の拒否は {@code FeatureEnablementSchemaMySqlTest}（mysql shard 1）が確かめる。
 */
class FeatureEnablementEventMapperImmutabilityTest {

    @Test
    void 履歴のmapperは全ての更新と削除を拒否し追記と読取は使える() {
        AppendOnlyMapperContract.assertAppendOnly(FeatureEnablementEventMapper.class, FeatureEnablementEvent.class,
                "t_feature_enablement_event");
    }

    @Test
    void 履歴のentityを一括の更新経路で包まない() throws Exception {
        AppendOnlyMapperContract.assertNotWrapped(FeatureEnablementEvent.class);
    }
}
