package com.ses.importcenter.foundation;

import java.util.Map;

public record SourceRow(long sourceRowNumber, String sourceRowHash, Map<String, String> values) {
    public SourceRow { values = Map.copyOf(values); }
}
