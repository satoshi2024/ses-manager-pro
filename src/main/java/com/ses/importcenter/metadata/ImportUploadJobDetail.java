package com.ses.importcenter.metadata;

/** UPLOADED jobのPII-free response allowlist。 */
public record ImportUploadJobDetail(long jobId, String state, String entity, String sourceHashPrefix,
                                    int documentVersionNo, String encoding) { }
