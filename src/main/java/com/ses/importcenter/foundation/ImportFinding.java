package com.ses.importcenter.foundation;

/** PII本文を含めず行番号・hash・固定codeで問題を示す。 */
public record ImportFinding(long sourceRowNumber, String sourceRowHash, String code, String message) { }
