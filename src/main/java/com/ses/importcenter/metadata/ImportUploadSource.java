package com.ses.importcenter.metadata;

/** current authorization下でtenant-scopedに解決したsource snapshot。 */
public record ImportUploadSource(long id, String sourceCode, long version) { }
