package com.ses.service.impl;

import com.ses.entity.MailDelivery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * RB-POSTLAUNCH P3-A: {@code MailServiceImpl.copyForSend} は非同期送信へ渡す配信履歴を項目ごとに手で複製している。
 * {@link MailDelivery} に項目を足して複製し忘れると、{@code executeSend} が新しい項目を受け取れない
 * （{@code updateById} は null を飛ばすためDBは壊れないが、送信内容から黙って欠ける）。
 * entity の全 field（親classを含む）を reflection で列挙し、全てが複製されることを確かめる。
 */
class MailServiceCopyForSendFieldCoverageTest {

    @Test
    void 非同期送信へ渡す複製は配信履歴の全fieldを写す() throws Exception {
        MailDelivery source = new MailDelivery();
        List<Field> fields = fields(MailDelivery.class);
        LocalDateTime base = LocalDateTime.of(2026, 11, 18, 9, 0);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            field.set(source, sample(field, i, base));
        }

        Method copyForSend = MailServiceImpl.class.getDeclaredMethod("copyForSend", MailDelivery.class);
        copyForSend.setAccessible(true);
        MailDelivery copy = (MailDelivery) copyForSend.invoke(null, source);

        assertThat(copy).isNotSameAs(source);
        List<String> missing = new ArrayList<>();
        for (Field field : fields) {
            if (field.get(copy) == null || !field.get(copy).equals(field.get(source))) {
                missing.add(field.getName());
            }
        }
        assertThat(missing).as("copyForSend が写していない field（MailDelivery に足した項目は複製にも足す）").isEmpty();
        assertThat(fields).as("field を列挙できていること").hasSizeGreaterThanOrEqualTo(15);
    }

    /** static・synthetic を除く instance field（親classを含む）。 */
    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    /** field ごとに異なる値（同じ値が偶然一致して写し忘れを見逃さないように）。 */
    private static Object sample(Field field, int index, LocalDateTime base) {
        Class<?> type = field.getType();
        if (type == Long.class || type == long.class) {
            return 1000L + index;
        }
        if (type == Integer.class || type == int.class) {
            return 100 + index;
        }
        if (type == String.class) {
            return "value-" + field.getName();
        }
        if (type == LocalDateTime.class) {
            return base.plusMinutes(index);
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        return fail("MailDelivery." + field.getName() + " の型 " + type.getName()
                + " の値の作り方をこのtestへ足すこと（copyForSend の複製漏れを検出するため）");
    }
}
