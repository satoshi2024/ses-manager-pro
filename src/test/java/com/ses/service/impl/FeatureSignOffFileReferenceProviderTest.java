package com.ses.service.impl;

import com.ses.mapper.FeatureSignOffMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A02（design §6.2 の1）: 機能の開放の署名の文書の保存名を、消去（PURGED）していない署名について孤児清掃から守る。
 * 照会は {@code status <> 'PURGED' AND stored_name IS NOT NULL} で行い（{@link FeatureSignOffMapper#selectLiveStoredNames}）、
 * 照会の失敗は清掃のその回を止めるため例外をそのまま返す。
 */
class FeatureSignOffFileReferenceProviderTest {

    private final FeatureSignOffMapper mapper = mock(FeatureSignOffMapper.class);
    private final FeatureSignOffFileReferenceProvider provider = new FeatureSignOffFileReferenceProvider(mapper);

    @Test
    void 消去していない署名の保存名を空白と空を除いてclaimする() {
        when(mapper.selectLiveStoredNames()).thenReturn(Arrays.asList("a.pdf", " b.pdf ", "", null, "a.pdf"));

        assertThat(provider.referencedFileNames()).containsExactly("a.pdf", "b.pdf");
    }

    @Test
    void 照会はPURGED以外で保存名のある行だけを数える() throws Exception {
        String sql = String.join(" ", FeatureSignOffMapper.class.getMethod("selectLiveStoredNames")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());

        assertThat(sql).contains("status <> 'PURGED'").contains("stored_name IS NOT NULL")
                .as("清掃は upload root を tenant に関係なく共有するため全 tenant を返す").doesNotContain("tenant_id");
    }

    @Test
    void 結果が無ければ空で照会の失敗は例外のまま返す() {
        when(mapper.selectLiveStoredNames()).thenReturn(null);
        assertThat(provider.referencedFileNames()).isEmpty();
        when(mapper.selectLiveStoredNames()).thenReturn(List.of());
        assertThat(provider.referencedFileNames()).isEmpty();

        when(mapper.selectLiveStoredNames()).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(provider::referencedFileNames).isInstanceOf(IllegalStateException.class);
    }
}
