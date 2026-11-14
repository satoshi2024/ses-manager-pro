package com.ses.privacy.production.retention.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C-2 の前提: 分類が確定していない保存値は全て UNKNOWN（fail-closed）として読む。 */
class SubjectAttributionTest {

    @Test
    void 完全一致する4値だけをその分類として読む() {
        assertEquals(SubjectAttribution.ATTRIBUTABLE, SubjectAttribution.fromStored("ATTRIBUTABLE"));
        assertEquals(SubjectAttribution.NOT_ATTRIBUTABLE, SubjectAttribution.fromStored("NOT_ATTRIBUTABLE"));
        assertEquals(SubjectAttribution.NO_PERSONAL_DATA, SubjectAttribution.fromStored("NO_PERSONAL_DATA"));
        assertEquals(SubjectAttribution.UNKNOWN, SubjectAttribution.fromStored("UNKNOWN"));
        assertTrue(SubjectAttribution.ATTRIBUTABLE.isClassified());
        assertFalse(SubjectAttribution.UNKNOWN.isClassified());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not_attributable", " NOT_ATTRIBUTABLE", "NOT_ATTRIBUTABLE ", "NONE", "NO_PERSONAL",
            "attributable"})
    void 未知や表記ゆれの値は帰属できないと読み替えずUNKNOWNにする(String stored) {
        SubjectAttribution value = SubjectAttribution.fromStored(stored);

        assertEquals(SubjectAttribution.UNKNOWN, value);
        assertFalse(value.isClassified());
    }
}
