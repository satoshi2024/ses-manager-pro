package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-015（design.md §6.12・D-P2-7。BWQ-AC-013-02）: main の code の file を変える呼出しを bytecode から列挙し、既知の
 * 一覧と 3 つの分類で照合する（DB・Docker 不要）。
 *
 * <ol>
 *   <li>{@link Category#MUTATES}: upload root の下の file を変える。{@code guard} の method（同じ class か別の class）が、変える前に
 *       {@link BackupWriteGate#assertUploadsMutationAllowed} か {@link BackupWriteGate#writesBlockedForCurrentThread} を（直接、または
 *       同じ class の method を通して）呼ぶこと。</li>
 *   <li>{@link Category#DIRECTORY_ONLY}: upload root の下で directory を作るだけ（内容を変えない）。assert なし。backup の uploads の
 *       複製（{@code providers/uploads-local.sh}）は file だけを写し（{@code find -type f}）、空の directory は inventory に影響しない。
 *       bean の初期化の時（prod の UNARMED）にも動く。</li>
 *   <li>{@link Category#OUTSIDE}: upload root の外（system の一時 directory・CLI の出力先）。assert なし。理由を書く。</li>
 * </ol>
 * 一覧に無い呼出しが増えたら失敗する（新しい file の変更を理由と分類の無いまま入れさせない）。key は
 * {@code 最上位 class#method:API}（lambda の本体は外側の method の名前）。
 */
class UploadsMutationInventoryTest {

    enum Category {
        MUTATES, DIRECTORY_ONLY, OUTSIDE
    }

    record Entry(Category category, String guard, String reason) {
    }

    private static final String SES = "com.ses.";
    private static final String GATE = "com/ses/backup/quiesce/BackupWriteGate";
    private static final Set<String> GUARD_CALLS = Set.of("assertUploadsMutationAllowed", "writesBlockedForCurrentThread");

    private static final Map<String, Entry> INVENTORY = inventory(
            // FileStorageServiceImpl（upload root の quarantine・published。§6.12 の保存・公開の移動・削除・再 scan の移動）
            dir("service.impl.FileStorageServiceImpl#storeBytes:Files.createDirectories", "quarantine・published の directory"),
            mutates("service.impl.FileStorageServiceImpl#storeBytes:Files.write", "service.impl.FileStorageServiceImpl#storeBytes",
                    "upload の実体を quarantine へ書く"),
            mutates("service.impl.FileStorageServiceImpl#storeBytes:Files.move", "service.impl.FileStorageServiceImpl#storeBytes",
                    "scan が CLEAN なら published へ移す"),
            mutates("service.impl.FileStorageServiceImpl#delete:Files.deleteIfExists",
                    "service.impl.FileStorageServiceImpl#delete", "実体の削除"),
            dir("service.impl.FileStorageServiceImpl#rescan:Files.createDirectories", "published の directory"),
            mutates("service.impl.FileStorageServiceImpl#rescan:Files.move", "service.impl.FileStorageServiceImpl#rescan",
                    "再 scan が CLEAN なら published へ移す（metadata を書けなければ戻す）"),
            mutates("service.impl.FileStorageServiceImpl#deleteQuietly:Files.deleteIfExists",
                    "service.impl.FileStorageServiceImpl#storeBytes",
                    "保存に失敗した実体の後始末。storeBytes の assert の後（discardServerGenerated を含む）と、登録されなかった"
                            + "署名の文書の削除（discardUnregistered。自身も assert を呼んでから消す。review rb-fe-a1 1a4fd48d 差分"
                            + " P3-N1）だけで呼ばれる"),
            // LocalDocumentStorage（upload root/documents の quarantine・published）
            dir("service.storage.impl.LocalDocumentStorage#<init>:Files.createDirectories",
                    "bean の初期化の時の quarantine・published の directory（prod の UNARMED でも動く。D-P2-7）"),
            dir("service.storage.impl.LocalDocumentStorage#put:Files.createDirectories", "key の親 directory"),
            mutates("service.storage.impl.LocalDocumentStorage#put:Files.copy",
                    "service.storage.impl.LocalDocumentStorage#put", "文書の実体を書く"),
            mutates("service.storage.impl.LocalDocumentStorage#put:Files.deleteIfExists",
                    "service.storage.impl.LocalDocumentStorage#put", "書き損じた実体の後始末"),
            dir("service.storage.impl.LocalDocumentStorage#promote:Files.createDirectories", "公開先の親 directory"),
            mutates("service.storage.impl.LocalDocumentStorage#promote:Files.move",
                    "service.storage.impl.LocalDocumentStorage#promote", "quarantine から published へ移す"),
            mutates("service.storage.impl.LocalDocumentStorage#delete:Files.deleteIfExists",
                    "service.storage.impl.LocalDocumentStorage#delete", "実体の削除"),
            mutates("service.storage.impl.LocalDocumentStorage#deleteStrict:Files.deleteIfExists",
                    "service.storage.impl.LocalDocumentStorage#deleteStrict", "実体の削除（失敗を投げる）"),
            // ContractDocumentServiceImpl（upload root/contracts/{id}。§6.12 の PDF の書込み）
            dir("service.impl.ContractDocumentServiceImpl#create:Files.createDirectories", "contracts/{id} の directory"),
            mutates("service.impl.ContractDocumentServiceImpl#create:Files.newOutputStream",
                    "service.impl.ContractDocumentServiceImpl#create", "契約書の PDF を書く"),
            // ImportStageReconciliationLedger（upload root 直下の台帳の directory。§6.12）
            mutates("importcenter.metadata.ImportStageReconciliationLedger#remove:Files.deleteIfExists",
                    "importcenter.metadata.ImportStageReconciliationLedger#remove",
                    "照合を終えた台帳の削除（ImportStageReconciler。scheduler の permit の下）"),
            dir("importcenter.metadata.ImportStageReconciliationLedger#write:Files.createDirectories", "台帳の directory"),
            mutates("importcenter.metadata.ImportStageReconciliationLedger#write:Files.createTempFile",
                    "importcenter.metadata.ImportStageReconciliationLedger#write", "台帳の一時 file"),
            mutates("importcenter.metadata.ImportStageReconciliationLedger#write:Files.newOutputStream",
                    "importcenter.metadata.ImportStageReconciliationLedger#write", "台帳の内容を書く"),
            mutates("importcenter.metadata.ImportStageReconciliationLedger#write:Files.move",
                    "importcenter.metadata.ImportStageReconciliationLedger#write", "台帳の一時 file を確定する"),
            mutates("importcenter.metadata.ImportStageReconciliationLedger#write:Files.deleteIfExists",
                    "importcenter.metadata.ImportStageReconciliationLedger#write", "確定できなかった一時 file の後始末"),
            // LegacyUploadMigrationService（upload root 直下 → quarantine・published。起動時の処理。startup permit の下）
            dir("service.impl.LegacyUploadMigrationService#migrate:Files.createDirectories", "quarantine・published の directory"),
            mutates("service.impl.LegacyUploadMigrationService#migrate:Files.move",
                    "service.impl.LegacyUploadMigrationService#migrate", "旧 upload を quarantine へ移す"),
            mutates("service.impl.LegacyUploadMigrationService#publish:Files.move",
                    "service.impl.LegacyUploadMigrationService#migrate", "CLEAN なら published へ移す（migrate からだけ呼ばれる）"),
            // FileCleanupServiceImpl（upload root・quarantine・published の孤児の削除。scheduler の permit の下）
            mutates("service.impl.FileCleanupServiceImpl#cleanupOrphanFiles:Files.delete",
                    "service.impl.FileCleanupServiceImpl#cleanupOrphanFiles", "孤児の file の削除"),
            // ImportRawTempFileStore（upload root/quarantine の取込の一時 file）・ImportCsvIngestionService
            mutates("importcenter.metadata.ImportRawTempFileStore#<init>:Files.deleteIfExists",
                    "importcenter.metadata.ImportRawTempFileStore#deleteOrReport",
                    "削除の関数の参照だけ。削除は deleteOrReport（静止中は消さずに孤児清掃へ委ねる）"),
            dir("importcenter.metadata.ImportRawTempFileStore#create:Files.createDirectories", "quarantine の directory"),
            mutates("importcenter.metadata.ImportRawTempFileStore#create:Files.createTempFile",
                    "importcenter.metadata.ImportRawTempFileStore#create", "取込の一時 file を作る"),
            mutates("importcenter.metadata.ImportRawTempFileStore#truncateQuietly:FileChannel.open",
                    "importcenter.metadata.ImportRawTempFileStore#deleteOrReport",
                    "削除できなかった一時 file を 0 byte へ切り詰める（deleteOrReport からだけ呼ばれる）"),
            mutates("importcenter.metadata.ImportCsvIngestionService#copyBounded:Files.newOutputStream",
                    "importcenter.metadata.ImportRawTempFileStore#create",
                    "ImportRawTempFileStore#create が作った一時 file（作る時に assert 済み）へ同じ要求の中で書く"),
            // upload root の外
            outside("service.impl.DocumentServiceImpl#writeToTempAndDigest:Files.createTempFile",
                    "system の一時 directory（java.io.tmpdir）に hash を計算するための一時 file を作る"),
            outside("service.impl.DocumentServiceImpl#writeToTempAndDigest:Files.copy", "上の一時 file へ書く"),
            outside("service.impl.DocumentServiceImpl#deleteTempFile:Files.deleteIfExists", "上の一時 file の削除"),
            outside("service.cloudsign.CloudSignApiClientImpl#streamToTempFile:Files.newOutputStream",
                    "CloudSign から受け取った PDF を system の一時 directory の file へ書く"),
            outside("service.cloudsign.CloudSignApiClientImpl#tempFile:Files.createTempFile", "上の一時 file を作る"),
            outside("service.cloudsign.CloudSignArtifactService#deleteQuietly:Files.deleteIfExists",
                    "上の一時 file の削除（文書台帳への登録は LocalDocumentStorage を通る）"),
            outside("nf01.preflight.LifecycleAuthorizationPreflight#write:Files.createDirectories",
                    "読むだけの CLI の報告の出力先（運用者が指定する directory。app の upload root ではない）"),
            outside("nf01.preflight.LifecycleAuthorizationPreflight#write:Files.writeString", "上の報告の file を書く"));

    @Test
    void mainのfileを変える呼出しは3分類の既知の一覧と一致しuploadsを変えるものはassertの下にある() throws IOException {
        Scan scan = new Scan();
        Path root = Path.of("target/classes/com/ses");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                scan.read(Files.readAllBytes(file));
            }
        }

        assertThat(scan.mutations).as("file を変える呼出しを見つけられない（走査の空振り）").hasSizeGreaterThan(20);
        Set<String> added = new TreeSet<>(scan.mutations);
        added.removeAll(INVENTORY.keySet());
        Set<String> removed = new TreeSet<>(INVENTORY.keySet());
        removed.removeAll(scan.mutations);
        assertThat(added).as("一覧に無い file の変更があります。upload root の下を変えるなら assert を置き、分類と理由を一覧に"
                + "足してください").isEmpty();
        assertThat(removed).as("一覧にあるが無くなった呼出しがあります。一覧から消してください").isEmpty();
        assertThat(unguarded(scan, INVENTORY)).as("upload root の下の file を変える呼出しの guard が assert を呼んでいません")
                .isEmpty();
        assertThat(INVENTORY.values()).allSatisfy(entry -> assertThat(entry.reason()).isNotBlank());
    }

    @Test
    void 一覧に無い呼出しとassertの無いguardを検出する() throws IOException {
        Scan scan = new Scan();
        for (Class<?> fixture : List.of(GuardedFixture.class, UnguardedFixture.class)) {
            try (InputStream stream = fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
                assertThat(stream).isNotNull();
                scan.read(stream.readAllBytes());
            }
        }
        String guarded = "backup.quiesce.UploadsMutationInventoryTest#writeGuarded:Files.write";
        String unguarded = "backup.quiesce.UploadsMutationInventoryTest#writeUnguarded:Files.write";
        String lambda = "backup.quiesce.UploadsMutationInventoryTest#deleteLater:Files.delete";

        assertThat(scan.mutations).contains(SES + guarded, SES + unguarded, SES + lambda);
        Map<String, Entry> fixtureInventory = inventory(
                mutates(guarded, "backup.quiesce.UploadsMutationInventoryTest#writeGuarded", "fixture"),
                mutates(unguarded, "backup.quiesce.UploadsMutationInventoryTest#writeUnguarded", "fixture"));
        assertThat(unguarded(scan, fixtureInventory)).containsExactly(SES + unguarded);
        Set<String> notListed = new TreeSet<>(scan.mutations);
        notListed.removeAll(fixtureInventory.keySet());
        assertThat(notListed).as("一覧に無い呼出し（lambda の本体を含む）を検出する").containsExactly(SES + lambda);
    }

    // ---------------------------------------------------------------- fixture

    static final class GuardedFixture {
        private final BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.INERT, false,
                java.time.Duration.ofSeconds(20), 10);

        void writeGuarded(Path path) throws IOException {
            check();
            Files.write(path, new byte[] {1});
        }

        private void check() {
            gate.assertUploadsMutationAllowed("fixture");
        }
    }

    static final class UnguardedFixture {
        void writeUnguarded(Path path) throws IOException {
            Files.write(path, new byte[] {1});
        }

        Runnable deleteLater(Path path) {
            return () -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // fixture
                }
            };
        }
    }

    // ---------------------------------------------------------------- 走査

    /** {@code MUTATES} の key のうち、guard の method が assert を呼ばないもの。 */
    private static Set<String> unguarded(Scan scan, Map<String, Entry> inventory) {
        Set<String> result = new TreeSet<>();
        inventory.forEach((key, entry) -> {
            if (entry.category() == Category.MUTATES && !scan.callsGuard(entry.guard())) {
                result.add(key);
            }
        });
        return result;
    }

    static final class Scan {
        /** 見つけた変更の呼出し（{@code 最上位 class#method:API}）。 */
        final Set<String> mutations = new TreeSet<>();
        /** {@code 最上位 class#method} → その method が呼ぶ（owner, name）。 */
        private final Map<String, Set<String>> calls = new HashMap<>();

        void read(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            String className = reader.getClassName().replace('/', '.');
            String topLevel = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    String method = topLevel + "#" + methodName(name);
                    Set<String> invoked = calls.computeIfAbsent(method, key -> new HashSet<>());
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callee, String desc,
                                                    boolean isInterface) {
                            record(owner, callee, desc);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String invoked0, String desc, Handle bootstrap,
                                                           Object... arguments) {
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle) {
                                    record(handle.getOwner(), handle.getName(), handle.getDesc());
                                }
                            }
                        }

                        private void record(String owner, String callee, String desc) {
                            invoked.add(owner + "." + callee);
                            String api = mutationApi(owner, callee, desc);
                            if (api != null) {
                                mutations.add(method + ":" + api);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        /** guard の method（{@code com.ses.} を除いた {@code class#method}）が gate の判定を直接・同じ class の method を通して呼ぶか。 */
        boolean callsGuard(String guard) {
            String method = SES + guard;
            Set<String> invoked = calls.get(method);
            if (invoked == null) {
                return false;
            }
            if (invoked.stream().anyMatch(Scan::isGateCheck)) {
                return true;
            }
            String owner = method.substring(0, method.indexOf('#')).replace('.', '/');
            for (String call : invoked) {
                int dot = call.lastIndexOf('.');
                if (call.substring(0, dot).equals(owner) || call.substring(0, dot).startsWith(owner + "$")) {
                    Set<String> helper = calls.get(method.substring(0, method.indexOf('#')) + "#"
                            + methodName(call.substring(dot + 1)));
                    if (helper != null && helper.stream().anyMatch(Scan::isGateCheck)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean isGateCheck(String call) {
            int dot = call.lastIndexOf('.');
            return call.substring(0, dot).equals(GATE) && GUARD_CALLS.contains(call.substring(dot + 1));
        }

        /** lambda の本体（{@code lambda$外側$n}）は外側の method の名前にする。 */
        private static String methodName(String name) {
            if (name.startsWith("lambda$")) {
                String rest = name.substring("lambda$".length());
                int end = rest.indexOf('$');
                return end < 0 ? rest : rest.substring(0, end);
            }
            return name;
        }

        private static String mutationApi(String owner, String name, String desc) {
            switch (owner) {
                case "java/nio/file/Files":
                    if (name.equals("copy") && desc.startsWith("(Ljava/nio/file/Path;Ljava/io/OutputStream;")) {
                        return null; // path から stream へ読むだけ
                    }
                    return Set.of("write", "writeString", "copy", "move", "delete", "deleteIfExists", "createDirectories",
                            "createDirectory", "createFile", "createTempFile", "createTempDirectory", "newOutputStream",
                            "newBufferedWriter", "newByteChannel", "setLastModifiedTime", "setAttribute",
                            "setPosixFilePermissions", "setOwner", "createLink", "createSymbolicLink").contains(name)
                            ? "Files." + name : null;
                case "java/nio/channels/FileChannel":
                case "java/nio/channels/AsynchronousFileChannel":
                    return name.equals("open") ? owner.substring(owner.lastIndexOf('/') + 1) + ".open" : null;
                case "java/io/FileOutputStream":
                case "java/io/RandomAccessFile":
                case "java/io/FileWriter":
                    return name.equals("<init>") ? owner.substring(owner.lastIndexOf('/') + 1) + ".<init>" : null;
                case "java/io/PrintWriter":
                case "java/io/PrintStream":
                    return name.equals("<init>")
                            && (desc.startsWith("(Ljava/lang/String;") || desc.startsWith("(Ljava/io/File;"))
                            ? owner.substring(owner.lastIndexOf('/') + 1) + ".<init>" : null;
                case "java/io/File":
                    return Set.of("delete", "deleteOnExit", "renameTo", "mkdir", "mkdirs", "createNewFile", "createTempFile",
                            "setLastModified", "setReadOnly", "setWritable", "setReadable", "setExecutable").contains(name)
                            ? "File." + name : null;
                case "org/springframework/web/multipart/MultipartFile":
                    return name.equals("transferTo") ? "MultipartFile.transferTo" : null;
                case "org/springframework/util/FileCopyUtils":
                    return name.equals("copy") && desc.contains("Ljava/io/File;") ? "FileCopyUtils.copy" : null;
                case "org/springframework/util/FileSystemUtils":
                    return name.equals("deleteRecursively") || name.equals("copyRecursively")
                            ? "FileSystemUtils." + name : null;
                default:
                    return null;
            }
        }
    }

    // ---------------------------------------------------------------- 一覧

    private static Map.Entry<String, Entry> mutates(String key, String guard, String reason) {
        return Map.entry(SES + key, new Entry(Category.MUTATES, guard, reason));
    }

    private static Map.Entry<String, Entry> dir(String key, String reason) {
        return Map.entry(SES + key, new Entry(Category.DIRECTORY_ONLY, null, reason));
    }

    private static Map.Entry<String, Entry> outside(String key, String reason) {
        return Map.entry(SES + key, new Entry(Category.OUTSIDE, null, reason));
    }

    @SafeVarargs
    private static Map<String, Entry> inventory(Map.Entry<String, Entry>... entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> entry : entries) {
            if (map.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalStateException("一覧の key が重複しています: " + entry.getKey());
            }
        }
        return Map.copyOf(map);
    }
}
