package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEncoding;

/** raw upload headerから作られる、非権威のsource selector/encodingだけを保持する。 */
public record ImportRawUploadHeaders(String sourceCode, ImportEncoding encoding) { }
