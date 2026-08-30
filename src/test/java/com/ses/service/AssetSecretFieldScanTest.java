package com.ses.service;

import com.ses.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Asset / Account / License Secret Field Scan Test (秘密非保存検証)")
class AssetSecretFieldScanTest {

    private static final List<String> FORBIDDEN_SECRET_KEYWORDS = Arrays.asList(
            "password", "secret", "token", "recoverycode", "credential", "privatekey", "clientsecret"
    );

    private static final List<Class<?>> TARGET_CLASSES = Arrays.asList(
            Asset.class,
            AssetAssignment.class,
            AssetEvent.class,
            AssetInventoryRun.class,
            AssetInventoryItem.class,
            ExternalAccountSystem.class,
            ExternalAccountReference.class,
            LicensePlan.class,
            LicenseAssignment.class
    );

    @Test
    @DisplayName("No secret fields exist in asset & account entities")
    void testNoSecretFieldsInEntities() {
        for (Class<?> clazz : TARGET_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                for (String keyword : FORBIDDEN_SECRET_KEYWORDS) {
                    assertThat(fieldName)
                            .withFailMessage("Class %s contains forbidden secret field: %s", clazz.getSimpleName(), field.getName())
                            .doesNotContain(keyword);
                }
            }
        }
    }
}
