package com.ses.mapper;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.common.mybatis.AppendOnlyMapper;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.core.ResolvableType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追記専用の表の mapper（{@link AppendOnlyMapper} を継承する）の共通の検査（FE-A02 の3表。先例
 * {@code LifecycleDualConfirmationMapperImmutabilityTest}）。MyBatis-Plus の mapper proxy と同じく「default method は本体を実行し、
 * それ以外は SQL を実行する」proxy で呼び、{@link BaseMapper} の更新・削除・insertOrUpdate の全ての method が SQL へ到達せず、
 * 表名を含む {@link UnsupportedOperationException} になることを確かめる。
 */
final class AppendOnlyMapperContract {

    private AppendOnlyMapperContract() {
    }

    /** mapper が共通基を直接継承し、更新系を再定義せず、全ての更新・削除を拒否し、追記と読取は継承のまま使えること。 */
    static void assertAppendOnly(Class<?> mapper, Class<?> entity, String table) {
        assertThat(mapper.isAnnotationPresent(Mapper.class)).as(mapper.getSimpleName()).isTrue();
        assertThat(mapper.getInterfaces()).as("%s は AppendOnlyMapper を直接継承する", mapper.getSimpleName())
                .contains(AppendOnlyMapper.class);
        assertThat(ResolvableType.forClass(mapper).as(AppendOnlyMapper.class).resolveGeneric(0))
                .as(mapper.getSimpleName()).isEqualTo(entity);
        assertThat(entity.getAnnotation(TableName.class).value()).isEqualTo(table);

        Set<String> modifyingNames = modifyingBaseMapperMethods().stream().map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> violations = new ArrayList<>();
        for (Method declared : mapper.getDeclaredMethods()) {
            if (!declared.isSynthetic() && !declared.isBridge() && modifyingNames.contains(declared.getName())) {
                violations.add(mapper.getSimpleName() + "#" + signature(declared) + ": 更新系を再定義している");
            }
        }
        assertThat(Arrays.stream(mapper.getDeclaredMethods()).map(Method::getName))
                .as("%s は insert を上書きしない", mapper.getSimpleName()).doesNotContain("insert");

        Object proxy = sqlExecutingProxy(mapper);
        assertThat(invokeReturning(appendOnlyTableMethod(), proxy)).as("appendOnlyTable() は表名").isEqualTo(table);
        List<Method> modifying = modifyingBaseMapperMethods();
        assertThat(modifying).extracting(Method::getName).as("BaseMapper の更新系 method を読めている")
                .contains("updateById", "update", "deleteById", "delete", "deleteByMap", "deleteBatchIds", "insertOrUpdate");
        for (Method base : modifying) {
            String label = mapper.getSimpleName() + "#" + signature(base);
            Throwable thrown = invoke(base, proxy);
            if (!(thrown instanceof UnsupportedOperationException)) {
                violations.add(label + ": UnsupportedOperationException ではない: " + thrown);
            } else if (thrown.getMessage() == null || !thrown.getMessage().contains(table + " は追記専用")) {
                violations.add(label + ": 表名を含む理由が無い: " + thrown.getMessage());
            }
        }
        assertThat(violations).as("追記専用の表の mapper は更新・削除を SQL の発行前に拒否する").isEmpty();
    }

    /** entity を {@code IService}・{@code ServiceImpl}・{@code Db}・{@code SqlHelper} で包まない（statement ID で直接実行する経路）。 */
    static void assertNotWrapped(Class<?> entity) throws Exception {
        List<String> violations = new ArrayList<>();
        Path root = Path.of("src/main/java");
        String name = Pattern.quote(entity.getSimpleName());
        Pattern wrapped = Pattern.compile("\\b(?:IService|ServiceImpl)\\s*<[^;{]*\\b" + name + "\\b"
                + "|\\b(?:Db|SqlHelper)\\s*\\.\\s*\\w+\\s*\\([^;]*\\b" + name + "\\b");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (wrapped.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    violations.add(root.relativize(file).toString());
                }
            }
        }
        assertThat(violations).as("%s を一括の更新経路で包まない", entity.getSimpleName()).isEmpty();
    }

    private static List<Method> modifyingBaseMapperMethods() {
        return Arrays.stream(BaseMapper.class.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
                .filter(method -> method.getName().startsWith("update") || method.getName().startsWith("delete")
                        || method.getName().startsWith("insertOrUpdate") || method.getName().startsWith("saveOrUpdate"))
                .sorted((a, b) -> signature(a).compareTo(signature(b)))
                .toList();
    }

    private static Method appendOnlyTableMethod() {
        try {
            return AppendOnlyMapper.class.getMethod("appendOnlyTable");
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object sqlExecutingProxy(Class<?> mapper) {
        return Proxy.newProxyInstance(mapper.getClassLoader(), new Class<?>[]{mapper}, (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new AssertionError("SQL の実行へ到達した: " + method);
        });
    }

    private static Object invokeReturning(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(method + " が失敗した", failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Throwable invoke(Method method, Object target) {
        Object[] args = Arrays.stream(method.getParameterTypes()).map(AppendOnlyMapperContract::defaultValue).toArray();
        try {
            method.invoke(target, args);
            return null;
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object defaultValue(Class<?> type) {
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
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }
}
