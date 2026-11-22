package com.ses.service.storage.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N6-2: {@code deleteStrict}は削除失敗を呼出元へ返し、既存の{@code delete}は従来どおり失敗をログだけで扱う。
 *
 * <p>削除失敗は、削除対象pathに空でないdirectoryを置いて再現する（OSに依存せず{@code DirectoryNotEmptyException}になる）。
 */
class LocalDocumentStorageStrictDeleteTest {

    @TempDir
    Path base;

    @Test
    void strictDeleteRemovesQuarantineAndPublishedObjects() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());
        storage.put("abc/one", "a".getBytes(StandardCharsets.UTF_8), true);
        storage.put("abc/one", "b".getBytes(StandardCharsets.UTF_8), false);

        storage.deleteStrict("abc/one");

        assertFalse(Files.exists(base.resolve("documents/quarantine/abc/one")));
        assertFalse(Files.exists(base.resolve("documents/published/abc/one")));
    }

    @Test
    void strictDeleteOfMissingObjectIsIdempotent() {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());

        assertDoesNotThrow(() -> storage.deleteStrict("abc/missing"));
    }

    @Test
    void strictDeleteThrowsWhenTheObjectCannotBeDeletedAndStillTriesTheOtherArea() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());
        Path blocked = base.resolve("documents/quarantine/abc/two");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("child"), "x");
        storage.put("abc/two", "published".getBytes(StandardCharsets.UTF_8), false);

        IOException failure = assertThrows(IOException.class, () -> storage.deleteStrict("abc/two"));

        assertTrue(failure.getMessage().contains("abc/two"));
        assertTrue(Files.exists(blocked), "削除できなかった実体は残る");
        assertFalse(Files.exists(base.resolve("documents/published/abc/two")), "もう一方の領域も削除を試みる");
    }

    @Test
    void legacyDeleteKeepsSwallowingTheSameFailure() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());
        Path blocked = base.resolve("documents/quarantine/abc/three");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("child"), "x");

        assertDoesNotThrow(() -> storage.delete("abc/three"));
        assertTrue(Files.exists(blocked));
    }

    @Test
    void strictDeleteRejectsBlankKey() {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());

        assertThrows(IllegalArgumentException.class, () -> storage.deleteStrict(" "));
    }

    // ---------------------------------------------------------------- backup の書込み静止（RB-QUIESCE BWQ-015）

    @Test
    void 静止中にpermitの無いthreadからの保存と移動と削除は拒否しfileを変えない() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());
        com.ses.backup.quiesce.BackupWriteGate gate = com.ses.backup.quiesce.BackupQuiesceTestSupport.openGate(false,
                () -> 0L, java.time.Clock.systemUTC());
        storage.setBackupWriteGate(gate);
        storage.put("abc/kept", "q".getBytes(StandardCharsets.UTF_8), true);
        storage.put("abc/pub", "p".getBytes(StandardCharsets.UTF_8), false);
        com.ses.backup.quiesce.BackupQuiesceTestSupport.close(gate, 1, java.time.Duration.ofMinutes(5));

        org.junit.jupiter.api.Assertions.assertThrows(com.ses.backup.quiesce.BackupWriteQuiescedException.class,
                () -> storage.put("abc/new", "n".getBytes(StandardCharsets.UTF_8), true));
        org.junit.jupiter.api.Assertions.assertThrows(com.ses.backup.quiesce.BackupWriteQuiescedException.class,
                () -> storage.promote("abc/kept"));
        org.junit.jupiter.api.Assertions.assertThrows(com.ses.backup.quiesce.BackupWriteQuiescedException.class,
                () -> storage.delete("abc/pub"));
        org.junit.jupiter.api.Assertions.assertThrows(com.ses.backup.quiesce.BackupWriteQuiescedException.class,
                () -> storage.deleteStrict("abc/pub"));

        assertFalse(Files.exists(base.resolve("documents/quarantine/abc/new")));
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(base.resolve("documents/quarantine/abc/kept")));
        assertFalse(Files.exists(base.resolve("documents/published/abc/kept")));
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(base.resolve("documents/published/abc/pub")));
    }

    @Test
    void 閉じる前にpermitを取ったthreadは静止中もfileを変えられる() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(base.toString());
        com.ses.backup.quiesce.BackupWriteGate gate = com.ses.backup.quiesce.BackupQuiesceTestSupport.openGate(false,
                () -> 0L, java.time.Clock.systemUTC());
        storage.setBackupWriteGate(gate);
        com.ses.backup.quiesce.BackupWritePermit permit = gate.tryAcquire(
                com.ses.backup.quiesce.BackupWritePermit.Kind.HTTP, "POST /api/documents");
        try {
            com.ses.backup.quiesce.BackupQuiesceTestSupport.beginClosing(gate, 1);
            storage.put("abc/one", "a".getBytes(StandardCharsets.UTF_8), true);
            storage.promote("abc/one");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(base.resolve("documents/published/abc/one")));
            storage.deleteStrict("abc/one");
        } finally {
            permit.close();
        }
        assertFalse(Files.exists(base.resolve("documents/published/abc/one")));
    }

    @Test
    void prodの登録の前でもbeanの初期化のdirectoryの作成は拒否しない() {
        com.ses.backup.quiesce.BackupWriteGate unarmed = com.ses.backup.quiesce.BackupQuiesceTestSupport.unarmedGate(
                true, () -> 0L, java.time.Clock.systemUTC());
        org.junit.jupiter.api.Assertions.assertTrue(unarmed.writesBlockedForCurrentThread());

        LocalDocumentStorage storage = new LocalDocumentStorage(base.resolve("prod").toString());
        storage.setBackupWriteGate(unarmed);

        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(base.resolve("prod/documents/quarantine")));
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(base.resolve("prod/documents/published")));
        org.junit.jupiter.api.Assertions.assertThrows(com.ses.backup.quiesce.BackupWriteQuiescedException.class,
                () -> storage.put("x", new byte[] {1}, true), "file の変更は prod の登録の前は拒否する");
    }
}
