package com.ses.importcenter.foundation;

import java.util.List;

public record ReferenceLookup(LookupStatus status, List<ReferenceTarget> candidates) {
    public ReferenceLookup { candidates = List.copyOf(candidates); }
    public static ReferenceLookup notFound() { return new ReferenceLookup(LookupStatus.NOT_FOUND, List.of()); }
}
