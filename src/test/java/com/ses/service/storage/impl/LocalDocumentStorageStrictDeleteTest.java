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
}
