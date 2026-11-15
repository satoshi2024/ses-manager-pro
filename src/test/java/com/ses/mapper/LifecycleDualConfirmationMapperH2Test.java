package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleDualEvent;
import com.ses.entity.LifecycleDualIdempotency;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF01 B0（W3-18、計画 §7.7 Demo(2)）: 実際のMyBatis-Plus mapper bean（H2のreplay schema）で、不変6表の更新・削除が
 * SQLの発行前に拒否され、追記の insert と読取は使えることを確かめる。
 *
 * <p>{@code AppendOnlySqlInjector} が不変6表のmapperへ更新・削除のMappedStatementを注入しないこと（statement IDでの直接実行も
 * 失敗すること）と、冪等性keyのCHECK（{@code ck_dual_idem_key}）が許可外の文字を拒否することも確かめる。
 * H2のreplayにはtriggerを置かないため、fast testではmapper層の拒否が唯一の防御である。DB triggerによる拒否は
 * {@code AuthorizationEpochSchemaMySqlTest}（M1）が実MySQLで確かめる。読むのは本testが挿入した行だけで、
 * 全行はクラスの {@code @Transactional} でrollbackする。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleDualConfirmationMapperH2Test {

    @Autowired private LifecycleDualSubmissionMapper submissionMapper;
    @Autowired private LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    @Autowired private LifecycleDualSubmissionOrgFenceMapper submissionOrgFenceMapper;
    @Autowired private LifecycleDualConfirmationMapper confirmationMapper;
    @Autowired private LifecycleDualIdempotencyMapper idempotencyMapper;
    @Autowired private LifecycleDualEventMapper eventMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionFactory sqlSessionFactory;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;

    private static final List<Class<?>> IMMUTABLE_MAPPERS = List.of(LifecycleDualSubmissionMapper.class,
            LifecycleDualSubmissionDocumentMapper.class, LifecycleDualSubmissionOrgFenceMapper.class,
            LifecycleDualConfirmationMapper.class, LifecycleDualIdempotencyMapper.class, LifecycleDualEventMapper.class);

    @Test
    void 追記専用mapperには更新削除のMappedStatementが注入されず他のmapperは従来どおり() {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        for (Class<?> mapper : IMMUTABLE_MAPPERS) {
            String namespace = mapper.getName();
            assertThat(configuration.hasStatement(namespace + ".insert")).as(namespace + ".insert").isTrue();
            assertThat(configuration.hasStatement(namespace + ".selectList")).as(namespace + ".selectList").isTrue();
            assertThat(configuration.hasStatement(namespace + ".selectCount")).as(namespace + ".selectCount").isTrue();
            for (String statement : List.of("update", "updateById", "delete", "deleteById", "deleteByIds")) {
                assertThat(configuration.hasStatement(namespace + "." + statement))
                        .as("%s.%s は注入されない", namespace, statement).isFalse();
            }
        }
        // 追記専用でないmapperには既定どおり更新・削除の文が注入される
        String projection = LifecycleDualProjectionMapper.class.getName();
        assertThat(configuration.hasStatement(projection + ".update")).isTrue();
        assertThat(configuration.hasStatement(projection + ".delete")).isTrue();
        assertThat(configuration.hasStatement(LifecycleTaskMapper.class.getName() + ".updateById")).isTrue();
        assertThat(configuration.hasStatement(LifecycleEventMapper.class.getName() + ".insert")).isTrue();
    }

    @Test
    void mapper_proxyを経由しないstatement_IDでの更新と削除は文が無いため失敗する() {
        String namespace = LifecycleDualSubmissionMapper.class.getName();
        assertThatThrownBy(() -> sqlSessionTemplate.update(namespace + ".updateById", Map.of()))
                .hasStackTraceContaining("Mapped Statements collection does not contain value");
        assertThatThrownBy(() -> sqlSessionTemplate.delete(namespace + ".delete", Map.of()))
                .hasStackTraceContaining("Mapped Statements collection does not contain value");
    }

    @Test
    void 冪等性keyのCHECKは空文字と末尾空白と改行と許可外の文字を拒否する() {
        for (String invalid : List.of("", "Key-A ", " Key-A", "key a", "key/a", "key\n", "key\t", "key+a")) {
            assertThatThrownBy(() -> idempotencyMapper.insert(idempotency(invalid)))
                    .as("key=[%s]", invalid)
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(failure -> assertThat(String.valueOf(((DataAccessException) failure)
                            .getMostSpecificCause().getMessage()).toLowerCase()).contains("ck_dual_idem_key"));
        }
        // 許可する文字だけのkeyはCHECKを通る（参照先が無いのでFKで失敗し得るが、CHECKでは失敗しない）
        for (String valid : List.of("Key-A", "a.b_c~d-1", "Z".repeat(190))) {
            try {
                idempotencyMapper.insert(idempotency(valid));
            } catch (DataAccessException failure) {
                assertThat(String.valueOf(failure.getMostSpecificCause().getMessage()).toLowerCase())
                        .as("key=[%s]", valid).doesNotContain("ck_dual_idem_key");
            }
        }
    }

    private static LifecycleDualIdempotency idempotency(String key) {
        return LifecycleDualIdempotency.builder().tenantId("nf01-b0-h2").caseId(9_000_101L).taskId(9_000_102L)
                .taskGeneration(0L).operation("SUBMIT").actorUserId(9_000_103L).idempotencyKey(key)
                .requestSha256("c".repeat(64)).submissionId(9_000_104L).build();
    }

    @Test
    void 不変6表のmapper_beanはBaseMapperの更新削除系メソッドをSQL発行前に拒否する() {
        Map<String, BaseMapper<?>> beans = new LinkedHashMap<>();
        beans.put("t_lifecycle_dual_submission", submissionMapper);
        beans.put("t_lifecycle_dual_submission_document", submissionDocumentMapper);
        beans.put("t_lifecycle_dual_submission_org_fence", submissionOrgFenceMapper);
        beans.put("t_lifecycle_dual_confirmation", confirmationMapper);
        beans.put("t_lifecycle_dual_idempotency", idempotencyMapper);
        beans.put("t_lifecycle_dual_event", eventMapper);

        List<Method> modifying = Arrays.stream(BaseMapper.class.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
                .filter(method -> method.getName().startsWith("update") || method.getName().startsWith("delete")
                        || method.getName().startsWith("insertOrUpdate"))
                .toList();
        assertThat(modifying).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, BaseMapper<?>> bean : beans.entrySet()) {
            for (Method method : modifying) {
                Throwable thrown = invoke(method, bean.getValue());
                if (!(thrown instanceof UnsupportedOperationException)
                        || !String.valueOf(thrown.getMessage()).contains(bean.getKey() + " は追記専用")) {
                    violations.add(bean.getKey() + "#" + method.getName() + Arrays.toString(method.getParameterTypes())
                            + ": " + thrown);
                }
            }
        }
        assertThat(violations).as("実mapper beanでも更新・削除はSQLの発行前に拒否されること").isEmpty();
    }

    @Test
    void 追記監査はinsertと読取ができ更新と削除は拒否されて行は変わらない() {
        LifecycleDualEvent event = LifecycleDualEvent.builder().tenantId("nf01-b0-h2").requestedTaskId(9_000_001L)
                .requestedSubmissionId(9_000_002L).actorUserId(9_000_003L).operation("CONFIRM").eventType("DENIED")
                .reasonCode("NOT_FOUND_OR_FOREIGN").httpStatus(404).attemptCount(1).build();
        assertThat(eventMapper.insert(event)).isEqualTo(1);
        assertThat(event.getId()).isNotNull();

        LifecycleDualEvent stored = eventMapper.selectById(event.getId());
        assertThat(stored.getOccurredAt()).as("発生日時はDBの既定値").isNotNull();
        assertThat(stored.getReasonCode()).isEqualTo("NOT_FOUND_OR_FOREIGN");

        // MyBatisのsession cacheが返す読取結果のinstanceは変えず、別のentityで更新を試みる
        LifecycleDualEvent changed = LifecycleDualEvent.builder().id(event.getId()).reasonCode("SCOPE_DENIED").build();
        assertThatThrownBy(() -> eventMapper.updateById(changed)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> eventMapper.deleteById(event.getId())).isInstanceOf(UnsupportedOperationException.class);
        // DBの行そのもの（cacheを経由しない）が変わっていない
        assertThat(jdbcTemplate.queryForObject("SELECT reason_code FROM t_lifecycle_dual_event WHERE id = ?",
                String.class, event.getId())).isEqualTo("NOT_FOUND_OR_FOREIGN");
    }

    @Test
    void 追記監査のCHECKは未知のevent種別を拒否する() {
        LifecycleDualEvent unknown = LifecycleDualEvent.builder().tenantId("nf01-b0-h2").actorUserId(9_000_004L)
                .operation("SUBMIT").eventType("UPDATED").build();
        assertThatThrownBy(() -> eventMapper.insert(unknown)).isInstanceOf(DataAccessException.class);
    }

    private static Throwable invoke(Method method, Object target) {
        Object[] args = Arrays.stream(method.getParameterTypes()).map(type -> {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("未対応の引数型: " + type);
            }
            return null;
        }).toArray();
        try {
            method.invoke(target, args);
            return null;
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
