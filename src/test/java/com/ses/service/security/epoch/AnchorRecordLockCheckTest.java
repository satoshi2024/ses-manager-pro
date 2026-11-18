package com.ses.service.security.epoch;

import com.ses.test.InnoDbRecordLockWaits.HeldLock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2・M3のwriter先行で使う「anchor記録上の共有lockだけをS→X昇格とみなす」判定を、Docker無しで固定する
 * （W3-15 delta review P1-D1）。権限group割当のinsertがFK検査（{@code assigned_by} → 操作者の {@code sys_user} 行）で
 * 付ける {@code S,REC_NOT_GAP} は同じ表・indexでも別の記録なので数えない。
 */
class AnchorRecordLockCheckTest {

    private static final long WRITER = 41;

    @Test
    void record_lockのENGINE_LOCK_IDから記録の位置を取り出し形式外はnullにする() {
        assertEquals("2:4:7", EpochFenceMySqlSupport.recordPosition("140220216180608:2:4:7:140220218566608"));
        assertNull(EpochFenceMySqlSupport.recordPosition("140220216180608:1059:140220218566608"), "table lockの形式");
        assertNull(EpochFenceMySqlSupport.recordPosition("140220216180608:2:x:7:1"));
        assertNull(EpochFenceMySqlSupport.recordPosition(null));
    }

    @Test
    void FK検査が別の記録に付けた共有lockはanchorの昇格とみなさない() {
        HeldLock anchor = lock("100:2:4:7:900", "X,REC_NOT_GAP", "12");
        HeldLock operatorRow = lock("100:2:4:3:901", "S,REC_NOT_GAP", "5");
        HeldLock operatorRowKeyInvisible = lock("100:2:4:3:902", "S,REC_NOT_GAP", null);

        assertEquals(List.of(), EpochFenceMySqlSupport.sharedLocksOnAnchorRecord(anchor,
                List.of(operatorRow, operatorRowKeyInvisible)));
    }

    @Test
    void anchor記録上の共有lockと位置を判定できない共有lockと鍵値の矛盾は問題として返す() {
        HeldLock anchor = lock("100:2:4:7:900", "X,REC_NOT_GAP", "12");
        HeldLock upgraded = lock("100:2:4:7:903", "S,REC_NOT_GAP", null);
        HeldLock unknownFormat = lock("opaque-lock-id", "S,REC_NOT_GAP", null);
        HeldLock contradicting = lock("100:2:4:7:904", "S,REC_NOT_GAP", "5");

        List<String> problems = EpochFenceMySqlSupport.sharedLocksOnAnchorRecord(anchor,
                List.of(upgraded, unknownFormat, contradicting));

        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("S→X昇格"), problems.get(0));
        assertTrue(problems.get(1).contains("判定できない"), problems.get(1));
        assertTrue(problems.get(2).contains("矛盾"), problems.get(2));
    }

    @Test
    void lock_IDの形式が想定外でも両方の鍵値が見えていれば鍵値で判定する() {
        HeldLock anchor = lock("opaque-anchor", "X,REC_NOT_GAP", "12");

        assertEquals(List.of(), EpochFenceMySqlSupport.sharedLocksOnAnchorRecord(anchor,
                List.of(lock("opaque-other", "S,REC_NOT_GAP", "5"))));
        assertEquals(1, EpochFenceMySqlSupport.sharedLocksOnAnchorRecord(anchor,
                List.of(lock("opaque-same", "S,REC_NOT_GAP", "12"))).size());
    }

    private static HeldLock lock(String lockId, String mode, String lockData) {
        return new HeldLock(WRITER, lockId, "100", "sys_user", "PRIMARY", mode, lockData);
    }
}
