package com.ses.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X3-2: break-glass username の判定は、前後空白を除き {@code toLowerCase(Locale.ROOT)} で ASCII の大文字小文字の違いを
 * 同一視する。{@code equalsIgnoreCase} のように無点の ı を I へ折り畳むと、DB では別の行になる口座を break-glass として
 * 扱ってしまう（DOC-FINAL review P2-1）。
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

    @Test
    void 無点のıや点付きのİを含む名前はadminと同じ口座として扱わない() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setBreakGlassUsernames(new LinkedHashSet<>(Set.of("admin")));

        assertThat("admın".equalsIgnoreCase("admin")).as("前提: equalsIgnoreCase は ı を I と同一視する").isTrue();
        assertThat(properties.isBreakGlassUsername("admın")).isFalse();
        assertThat(properties.isBreakGlassUsername("ADMıN")).isFalse();
        assertThat(properties.isBreakGlassUsername("ADMİN")).isFalse();
        assertThat(properties.isBreakGlassUsername("ADMIN")).isTrue();

        properties.setBreakGlassUsernames(new LinkedHashSet<>(Set.of("admın")));
        assertThat(properties.isBreakGlassUsername("admin")).as("逆向きも同一視しない").isFalse();
        assertThat(properties.isBreakGlassUsername("ADMIN")).isFalse();
    }
}
