package com.ses.importcenter.metadata;

/** 取込元の一覧・登録応答。tenantの識別子・鍵版・作成者は含めない。 */
public record ImportSourceView(long id, String sourceCode, String displayName, String status, long version) { }
