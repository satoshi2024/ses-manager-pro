package com.ses.importcenter.foundation;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 入力者が選択する文字コード。自動判定は行わない。 */
public enum ImportEncoding {
    UTF8(StandardCharsets.UTF_8), SHIFT_JIS(Charset.forName("Shift_JIS"));

    private final Charset charset;

    ImportEncoding(Charset charset) { this.charset = charset; }

    public Charset charset() { return charset; }
}
