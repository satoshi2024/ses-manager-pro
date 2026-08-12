package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.common.base.BaseEntity;
import com.ses.entity.ComplianceOperationLedger;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2-OP-01〜04: append-only eventとoperation ledgerの公開APIを静的に固定する。
 * BaseMapperを継承すると未意図のUPDATE/DELETEがcompile時に見えるため、
 * eventはINSERT/SELECT、operationはclaim/SELECT/CASだけを許可する。
 */
class ComplianceG2MapperContractTest {

    private static final Set<Class<?>> EVENT_MAPPERS = Set.of(
            ComplianceMappingApprovalEventMapper.class,
            ComplianceExternalReviewEventMapper.class,
            ComplianceMappingStatusEventMapper.class);

    @Test
    void eventMapperはBaseMapperと更新削除APIを公開しない() {
        for (Class<?> mapper : EVENT_MAPPERS) {
            assertFalse(BaseMapper.class.isAssignableFrom(mapper), mapper.getSimpleName());
            for (Method method : mapper.getDeclaredMethods()) {
                assertFalse(method.isAnnotationPresent(Update.class), mapper.getSimpleName() + "." + method.getName());
                assertFalse(method.isAnnotationPresent(Delete.class), mapper.getSimpleName() + "." + method.getName());
                assertTrue(method.isAnnotationPresent(Insert.class) || method.isAnnotationPresent(Select.class),
                        mapper.getSimpleName() + "." + method.getName() + "はINSERT/SELECTだけを許可します");
            }
        }
    }

    @Test
    void operationLedgerはCAS以外の汎用CRUDを公開しない() {
        assertFalse(BaseMapper.class.isAssignableFrom(ComplianceOperationLedgerMapper.class));
        assertFalse(BaseEntity.class.isAssignableFrom(ComplianceOperationLedger.class));
        Set<String> allowed = Set.of("insertClaim", "selectByIdempotencyKey", "selectByOperationId",
                "renewLeaseCas", "completeSuccessCas", "completeFailureCas", "restartFailedCas");
        for (Method method : ComplianceOperationLedgerMapper.class.getDeclaredMethods()) {
            assertTrue(allowed.contains(method.getName()), method.getName());
            assertFalse(method.getName().equals("deleteById") || method.getName().equals("updateById"),
                    method.getName());
            assertTrue(method.isAnnotationPresent(Insert.class)
                            || method.isAnnotationPresent(Select.class)
                            || method.isAnnotationPresent(Update.class),
                    method.getName() + "はclaim/select/CASだけを許可します");
        }

        String claimSql = getMethod("insertClaim").getAnnotation(Insert.class).value()[0];
        assertTrue(claimSql.contains("'PROCESSING'"));
        assertTrue(claimSql.contains("0, 1"), "claimのretryable/attempt初期値をserver側で固定します");
        assertTrue(claimSql.endsWith("0, 0)"), "claimのversion/deleted_flag初期値をserver側で固定します");
        Method failure = getMethod("completeFailureCas");
        String failureSql = failure.getAnnotation(Update.class).value()[0];
        assertTrue(failureSql.contains("retryable_flag"));
        assertTrue(failureSql.contains("result_reference_type = NULL"));
        assertTrue(failureSql.contains("result_reference_id = NULL"));
        assertTrue(failureSql.contains("result_summary_canonical = NULL"));
        assertTrue(failureSql.contains("result_http_status = NULL"));
        assertTrue(failureSql.contains("result_hash = NULL"));
        assertTrue(failure.getParameterCount() <= 7, "FAILED遷移へ成功result payloadを渡してはいけません");
        Method restart = getMethod("restartFailedCas");
        assertTrue(restart.getAnnotation(Update.class).value()[0].contains("state = 'FAILED'"));
        assertTrue(restart.getAnnotation(Update.class).value()[0].contains("retryable_flag = 1"));
    }

    private Method getMethod(String name) {
        for (Method method : ComplianceOperationLedgerMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("missing mapper method: " + name);
    }
}
