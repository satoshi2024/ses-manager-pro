package com.ses.architecture;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * bean の生成の間（constructor・{@code @PostConstruct}・{@code InitializingBean.afterPropertiesSet}）に業務の DB へ書かないことを、
 * main の bytecode から静的に確かめる（RB-QUIESCE 2a review P1-1。DB・Docker 不要）。
 *
 * <p>prod の profile で backup の書込み静止を有効にすると、静止応答が instance を登録するまで（UNARMED）は permit の無い業務の
 * 接続が読取り専用になる（design.md §6.2・L-13）。bean の生成はその前に終わるため、ここで書くと起動が止まる（bootstrap の再開の
 * 必須監査がそうだった）。書く処理は起動時の処理（{@code ApplicationRunner} 等。startup permit の下）へ移す。H2 の
 * {@code setReadOnly} は何もしないため、H2 の試験ではこの誤りを見つけられない。
 *
 * <p>走査: 入口の method から、同じ main の class の具象 method への呼出し（constructor・static・自分の method・他の class の
 * method）を辿り、次の書込みの呼出しを探す。interface の method は書込みの呼出しの判定だけを行い、実装へは辿らない。
 * <ul>
 *   <li>MyBatis の mapper（名前が {@code Mapper} で終わる型）の {@code insert}・{@code update*}・{@code delete*} と、
 *       {@code @Insert}・{@code @Update}・{@code @Delete} の method</li>
 *   <li>{@code AuditLogService}・{@code AuditLogServiceImpl} の {@code record*}</li>
 *   <li>{@code JdbcTemplate}・{@code NamedParameterJdbcTemplate}（と operations の interface）の {@code update}・
 *       {@code batchUpdate}・{@code execute}</li>
 *   <li>MyBatis-Plus の service（名前が {@code Service}・{@code ServiceImpl} で終わる型）の {@code save*}・{@code update*}・
 *       {@code remove*}</li>
 * </ul>
 */
class InitializationWriteGuardTest {

    /** 既知の例外（入口の method → 理由）。業務の DB へ書かないことを確かめたものだけを載せる。 */
    private static final Map<String, String> KNOWN = Map.of();

    private static final Set<String> MAPPER_BASE_WRITES = Set.of("insert", "insertOrUpdate", "update", "updateById",
            "delete", "deleteById", "deleteByMap", "deleteBatchIds", "deleteByIds");
    private static final Set<String> AUDIT_OWNERS = Set.of("com/ses/service/AuditLogService",
            "com/ses/service/impl/AuditLogServiceImpl");
    private static final Set<String> JDBC_OWNERS = Set.of("org/springframework/jdbc/core/JdbcTemplate",
            "org/springframework/jdbc/core/JdbcOperations",
            "org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate",
            "org/springframework/jdbc/core/namedparam/NamedParameterJdbcOperations");
    private static final Set<String> JDBC_WRITES = Set.of("update", "batchUpdate", "execute");
    private static final Set<String> MYBATIS_WRITE_ANNOTATIONS = Set.of("Lorg/apache/ibatis/annotations/Insert;",
            "Lorg/apache/ibatis/annotations/Update;", "Lorg/apache/ibatis/annotations/Delete;");
    private static final String POST_CONSTRUCT = "Ljakarta/annotation/PostConstruct;";
    private static final int MAX_DEPTH = 12;

    @Test
    void bean生成の間に業務のDBへ書く呼出しが無い() throws IOException {
        Scan scan = new Scan();
        try (Stream<Path> files = Files.walk(Path.of("target/classes"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                scan.read(Files.readAllBytes(file));
            }
        }
        Map<String, List<String>> findings = scan.findings(name -> name.startsWith("com/ses/"));

        assertThat(scan.entryCount).as("入口の method を見つけられない（走査の空振り）").isGreaterThan(100);
        assertThat(scan.mapperWrites).as("mapper の @Insert 等を読めない（走査の空振り）").isNotEmpty();
        Map<String, List<String>> unexpected = new LinkedHashMap<>(findings);
        KNOWN.keySet().forEach(unexpected::remove);
        assertThat(unexpected)
                .as("bean の生成の間（constructor・@PostConstruct・afterPropertiesSet）に業務の DB へ書いています。prod の静止の"
                        + "登録の前は読取り専用のため起動が止まります。ApplicationRunner 等の起動時の処理へ移してください")
                .isEmpty();
        assertThat(KNOWN.keySet()).as("既知の例外の一覧に、もう該当しない入口があります").allMatch(findings::containsKey);
    }

    @Test
    void 走査はPostConstructとafterPropertiesSetとconstructorから辿った書込みを検出する() throws IOException {
        Scan scan = new Scan();
        for (Class<?> type : List.of(PostConstructWriter.class, InitializingWriter.class, ConstructorWriter.class,
                Helper.class, ReadOnlyBean.class, FakeMapper.class, FakeAuditLogService.class)) {
            try (InputStream stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(stream).isNotNull();
                scan.read(stream.readAllBytes());
            }
        }

        Map<String, List<String>> findings = scan.findings(name -> name.startsWith("com/ses/architecture/"));

        assertThat(findings.keySet()).anyMatch(entry -> entry.contains("PostConstructWriter.init"))
                .anyMatch(entry -> entry.contains("InitializingWriter.afterPropertiesSet"))
                .anyMatch(entry -> entry.contains("ConstructorWriter.<init>"))
                .noneMatch(entry -> entry.contains("ReadOnlyBean"));
        assertThat(findings.values().stream().flatMap(List::stream).toList())
                .anyMatch(sink -> sink.endsWith("FakeMapper.markDone"))
                .anyMatch(sink -> sink.endsWith("FakeMapper.insert"))
                .anyMatch(sink -> sink.endsWith("JdbcTemplate.update"));
    }

    // ---------------------------------------------------------------- fixture（検出されることを確かめる）

    interface FakeMapper {
        int insert(Object entity);

        @org.apache.ibatis.annotations.Update("UPDATE x SET done = 1")
        int markDone();

        Object selectById(Object id);
    }

    interface FakeAuditLogService {
        void record(String user);
    }

    static final class Helper {
        static void write(FakeMapper mapper) {
            mapper.markDone();
        }
    }

    static final class PostConstructWriter {
        private final FakeMapper mapper;

        PostConstructWriter(FakeMapper mapper) {
            this.mapper = mapper;
        }

        @PostConstruct
        void init() {
            Helper.write(mapper);
        }
    }

    static final class InitializingWriter implements InitializingBean {
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

        InitializingWriter(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void afterPropertiesSet() {
            jdbc.update("DELETE FROM x");
        }
    }

    static final class ConstructorWriter {
        ConstructorWriter(FakeMapper mapper) {
            mapper.insert(new Object());
        }
    }

    static final class ReadOnlyBean {
        private final FakeMapper mapper;

        ReadOnlyBean(FakeMapper mapper) {
            this.mapper = mapper;
        }

        @PostConstruct
        void init() {
            mapper.selectById(1L);
        }
    }

    // ---------------------------------------------------------------- 走査

    /** 読んだ class の method と呼出しの一覧。 */
    static final class Scan {
        private final Map<String, String> superNames = new HashMap<>();
        private final Set<String> interfaces = new HashSet<>();
        private final Map<String, List<String[]>> calls = new HashMap<>();
        private final List<String> entries = new ArrayList<>();
        private final Set<String> mapperWrites = new HashSet<>();
        int entryCount;

        void read(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            String owner = reader.getClassName();
            superNames.put(owner, reader.getSuperName());
            boolean isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
            if (isInterface) {
                interfaces.add(owner);
            }
            reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    String key = owner + "." + name + descriptor;
                    List<String[]> invocations = new ArrayList<>();
                    calls.put(key, invocations);
                    if (name.equals("<init>") || name.equals("afterPropertiesSet") && descriptor.equals("()V")) {
                        entries.add(key);
                    }
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (POST_CONSTRUCT.equals(annotation) && !entries.contains(key)) {
                                entries.add(key);
                            }
                            if (MYBATIS_WRITE_ANNOTATIONS.contains(annotation)) {
                                mapperWrites.add(owner + "." + name);
                            }
                            return null;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String target, String method, String desc,
                                                    boolean itf) {
                            invocations.add(new String[] {target, method, desc});
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        /** 入口の method（{@code ownerFilter} に合う class のもの）→ 辿り着いた書込みの呼出し。 */
        Map<String, List<String>> findings(java.util.function.Predicate<String> ownerFilter) {
            Map<String, List<String>> findings = new LinkedHashMap<>();
            for (String entry : entries) {
                if (!ownerFilter.test(entry.substring(0, entry.indexOf('.')))) {
                    continue;
                }
                entryCount++;
                Set<String> sinks = new TreeSet<>();
                Set<String> visited = new HashSet<>();
                Deque<Object[]> queue = new ArrayDeque<>();
                queue.add(new Object[] {entry, 0});
                while (!queue.isEmpty()) {
                    Object[] next = queue.poll();
                    String method = (String) next[0];
                    int depth = (int) next[1];
                    if (!visited.add(method) || depth > MAX_DEPTH) {
                        continue;
                    }
                    for (String[] call : calls.getOrDefault(method, List.of())) {
                        if (isWrite(call[0], call[1])) {
                            sinks.add(method.substring(0, method.indexOf('(')) + " -> " + call[0] + "." + call[1]);
                            continue;
                        }
                        String resolved = resolve(call[0], call[1] + call[2]);
                        if (resolved != null) {
                            queue.add(new Object[] {resolved, depth + 1});
                        }
                    }
                }
                if (!sinks.isEmpty()) {
                    findings.put(entry.substring(0, entry.indexOf('(')), new ArrayList<>(sinks));
                }
            }
            return findings;
        }

        /** main の具象 class（とその親）の method。interface・main の外は辿らない。 */
        private String resolve(String owner, String nameAndDescriptor) {
            for (String type = owner; type != null && superNames.containsKey(type); type = superNames.get(type)) {
                if (interfaces.contains(type)) {
                    return null;
                }
                String key = type + "." + nameAndDescriptor;
                if (calls.containsKey(key)) {
                    return key;
                }
            }
            return null;
        }

        private boolean isWrite(String owner, String method) {
            if (owner.endsWith("Mapper") && (MAPPER_BASE_WRITES.contains(method) || mapperWrites.contains(owner + "." + method))) {
                return true;
            }
            if (AUDIT_OWNERS.contains(owner) && method.startsWith("record")) {
                return true;
            }
            if (JDBC_OWNERS.contains(owner) && JDBC_WRITES.contains(method)) {
                return true;
            }
            return (owner.endsWith("Service") || owner.endsWith("ServiceImpl"))
                    && (method.startsWith("save") || method.startsWith("update") || method.startsWith("remove"));
        }
    }
}
