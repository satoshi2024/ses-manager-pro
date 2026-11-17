package com.ses.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * w1b4 review P3-5（FINAL review §7 (b) b-5）: 案件・タスクの locking read は対象行の境界で照合する。
 *
 * <ul>
 *   <li>案件（{@code t_lifecycle_case}）の {@code FOR UPDATE}・{@code FOR SHARE} は全て {@code tenant_id = #{tenantId}} で照合し、
 *       tenant を引数に持つ。tenant を持たない {@code selectByIdForUpdate(Long)} は削除した（呼出し元が無く、誤用で他tenantの
 *       案件をlockできる口になるため）。</li>
 *   <li>タスク（{@code t_lifecycle_task}。tenant列を持たない）の locking read は、同じtransactionで先にlockした案件の
 *       {@code case_id} で照合する（案件 X → タスク X）。</li>
 * </ul>
 */
class LifecycleMapperTenantLockingReadTest {

    @Test
    void 案件のlocking_readは全てtenantで照合しtenantを持たない同名の読取を置かない() {
        List<String> checked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Method method : LifecycleCaseMapper.class.getDeclaredMethods()) {
            String sql = lockingSql(method);
            if (sql == null) {
                continue;
            }
            checked.add(method.getName());
            if (!sql.contains("TENANT_ID = #{TENANTID}") || !hasParam(method, "tenantId")) {
                problems.add(method.getName());
            }
        }
        assertThat(checked).as("案件の locking read が1件以上あること").isNotEmpty();
        assertThat(problems).as("tenant で照合しない案件の locking read").isEmpty();
        assertThat(Arrays.stream(LifecycleCaseMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("selectByIdForUpdate")))
                .as("tenant を持たない selectByIdForUpdate を置かない").isEmpty();
    }

    @Test
    void タスクのlocking_readは先にlockした案件のcase_idで照合する() {
        List<String> checked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Method method : LifecycleTaskMapper.class.getDeclaredMethods()) {
            String sql = lockingSql(method);
            if (sql == null) {
                continue;
            }
            checked.add(method.getName());
            if (!sql.contains("CASE_ID = #{CASEID}") || !hasParam(method, "caseId")) {
                problems.add(method.getName());
            }
        }
        assertThat(checked).as("タスクの locking read が1件以上あること").isNotEmpty();
        assertThat(problems).as("case_id で照合しないタスクの locking read").isEmpty();
    }

    /** {@code @Select} の SQL が locking read なら大文字にして返す（それ以外は null）。 */
    private static String lockingSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select == null) {
            return null;
        }
        String sql = String.join(" ", select.value()).toUpperCase(Locale.ROOT);
        return sql.contains("FOR UPDATE") || sql.contains("FOR SHARE") ? sql : null;
    }

    private static boolean hasParam(Method method, String name) {
        return Arrays.stream(method.getParameters()).map(parameter -> parameter.getAnnotation(Param.class))
                .anyMatch(param -> param != null && name.equals(param.value()));
    }
}
