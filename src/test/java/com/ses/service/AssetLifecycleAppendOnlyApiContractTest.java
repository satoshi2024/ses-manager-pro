package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 資産履歴サービスから汎用IServiceの更新・削除入口を公開しないことを検証する。 */
class AssetLifecycleAppendOnlyApiContractTest {

    private static final Set<String> MUTATION_NAMES = Set.of(
            "updateById", "update", "updateBatchById", "removeById", "removeByIds",
            "remove", "removeByMap", "saveOrUpdate", "saveOrUpdateBatch");

    @Test
    void appendOnly履歴サービスは汎用IServiceを継承しない() {
        assertFalse(IService.class.isAssignableFrom(AssetEventService.class));
        assertFalse(IService.class.isAssignableFrom(AssetAssignmentService.class));
        assertFalse(IService.class.isAssignableFrom(ExternalAccountService.class));
        assertFalse(IService.class.isAssignableFrom(AssetService.class));

        assertNoGenericMutation(AssetEventService.class);
        assertNoGenericMutation(AssetAssignmentService.class);
        assertNoGenericMutation(ExternalAccountService.class);
        assertNoGenericMutation(AssetService.class);
    }

    private void assertNoGenericMutation(Class<?> serviceType) {
        for (Method method : serviceType.getMethods()) {
            assertFalse(MUTATION_NAMES.contains(method.getName()),
                    () -> serviceType.getSimpleName() + " exposes generic mutation: " + method);
        }
    }
}
