package com.ses.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X3-2: break-glass username の判定を {@code sys_user.username} の照合順序（大文字小文字・末尾空白を区別しない）に合わせる。
 * 表記違いの入力も DB では同じ行へ解決されるため、判定も同じ口座として扱う（{@code SecurityConfig} の HTTP Basic 防護と同じ比較）。
 */
class OidcSecurityPropertiesTest {

    @Test
    void break_glass名は大文字小文字と前後空白の違いでも同じ口座として判定する() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setBreakGlassUsernames(new LinkedHashSet<>(Set.of("bg-operator", " Admin ")));

        assertThat(properties.isBreakGlassUsername("bg-operator")).isTrue();
        assertThat(properties.isBreakGlassUsername("BG-Operator")).isTrue();
        assertThat(properties.isBreakGlassUsername(" bg-operator ")).isTrue();
        assertThat(properties.isBreakGlassUsername("admin")).isTrue();
        assertThat(properties.isBreakGlassUsername("ADMIN")).isTrue();
    }

    @Test
    void 未設定_空_別名はbreak_glass名として扱わない() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        assertThat(properties.isBreakGlassUsername("admin")).as("既定は空").isFalse();

        properties.setBreakGlassUsernames(new LinkedHashSet<>(Arrays.asList("bg-operator", null, "  ")));
        assertThat(properties.isBreakGlassUsername(null)).isFalse();
        assertThat(properties.isBreakGlassUsername("")).isFalse();
        assertThat(properties.isBreakGlassUsername("   ")).as("空白だけの設定要素とも一致しない").isFalse();
        assertThat(properties.isBreakGlassUsername("bg-operator2")).isFalse();
        assertThat(properties.isBreakGlassUsername("bg-operato")).isFalse();
        assertThat(properties.isBreakGlassUsername("oidc-admin")).isFalse();

        properties.setBreakGlassUsernames(null);
        assertThat(properties.isBreakGlassUsername("bg-operator")).isFalse();
    }
}
