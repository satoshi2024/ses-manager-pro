package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NF01 F2（細粒度epoch計画 §2.6、W3-10 review P3-5）: engineer epochのanchorは、要求・tenant contextではなく
 * locking readした要員行の {@code tenant_id} で作り、IDかtenantが読めなければ409で止めることを固定する。
 */
class EngineerEpochAnchorsTest {

    @Test
    void anchorはlockした要員行のtenantとIDで作る() {
        Engineer locked = new Engineer();
        locked.setId(42L);
        locked.setTenantId("tenant-row");

        AuthorizationEpochAnchor.Engineer anchor = EngineerEpochAnchors.of(locked);

        assertEquals(AuthorizationEpochAnchor.engineer("tenant-row", 42L), anchor);
        assertEquals("tenant-row", anchor.partition());
    }

    @Test
    void tenantが未解決または空の行は409で止める() {
        for (String tenant : new String[] {null, "", " "}) {
            Engineer locked = new Engineer();
            locked.setId(42L);
            locked.setTenantId(tenant);
            BusinessException error = assertThrows(BusinessException.class, () -> EngineerEpochAnchors.of(locked));
            assertEquals(409, error.getCode());
            assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());
        }
    }

    @Test
    void 行かIDが無ければ409で止める() {
        BusinessException missingRow = assertThrows(BusinessException.class, () -> EngineerEpochAnchors.of(null));
        assertEquals(409, missingRow.getCode());
        Engineer withoutId = new Engineer();
        withoutId.setTenantId("tenant-row");
        BusinessException missingId = assertThrows(BusinessException.class, () -> EngineerEpochAnchors.of(withoutId));
        assertEquals(409, missingId.getCode());
    }
}
