package com.ses.importcenter.metadata;

/** source namespace作成に必要なcaller入力。tenant/actor/key versionは含めない。 */
public record ImportSourceRequest(String sourceCode, String displayName) { }
