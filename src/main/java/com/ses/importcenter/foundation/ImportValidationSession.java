package com.ses.importcenter.foundation;

import java.util.HashSet;
import java.util.Set;

/** job内cross-row重複を保持するbounded validator state。 */
public final class ImportValidationSession {
    private final Set<String> externalKeys = new HashSet<>();
    boolean firstKey(ImportEntity entity, String key) { return externalKeys.add(entity.name() + "\u0000" + key); }
}
