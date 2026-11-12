package com.ses.test;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * container束縛のテストコンテキストをクラス終了時に破棄する listener の判定と呼出し契約。
 *
 * <p>fixture は JUnit の discovery に拾われないよう private static とし、タグ値は定数経由で付ける
 * （{@code MySqlTestShardInventoryTest} はソース文字列の部分一致で mysql タグ付き test を棚卸しする）。</p>
 */
class ContainerBoundContextCloseListenerTest {

    private static final String MYSQL = "mysql";

    private final ContainerBoundContextCloseListener listener = new ContainerBoundContextCloseListener();

    @Test
    void Testcontainers付き最上位クラスはクラス終了時にコンテキストを破棄する() {
        TestContext context = contextFor(TestcontainersFixture.class, true);

        listener.afterTestClass(context);

        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }

    @Test
    void mysqlタグ付き最上位クラスはクラス終了時にコンテキストを破棄する() {
        TestContext context = contextFor(MySqlTaggedFixture.class, true);

        listener.afterTestClass(context);

        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }

    @Test
    void 未ロードのコンテキストでも破棄要求をSpringへ委譲する() {
        // Spring側はキャッシュに無いキーの破棄を何もしない。listener側で判定を重ねない。
        TestContext context = contextFor(TestcontainersFixture.class, false);

        listener.afterTestClass(context);

        verify(context).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }

    @Test
    void 継承_meta注釈_複数タグ_interfaceでもJUnitと同じくmysqlクラスと判定する() {
        assertThat(ContainerBoundContextCloseListener.closesContextAfterClass(InheritedTestcontainersFixture.class))
                .isTrue();
        assertThat(ContainerBoundContextCloseListener.closesContextAfterClass(InheritedTagFixture.class)).isTrue();
        assertThat(ContainerBoundContextCloseListener.closesContextAfterClass(MetaAnnotatedFixture.class)).isTrue();
        assertThat(ContainerBoundContextCloseListener.closesContextAfterClass(RepeatedTagsFixture.class)).isTrue();
        assertThat(ContainerBoundContextCloseListener.closesContextAfterClass(InterfaceTaggedFixture.class)).isTrue();
    }

    @Test
    void H2の通常クラスはコンテキストを破棄せずキャッシュを維持する() {
        TestContext plain = contextFor(PlainFixture.class, true);
        TestContext otherTag = contextFor(OtherTagFixture.class, true);

        listener.afterTestClass(plain);
        listener.afterTestClass(otherTag);

        verify(plain, never()).markApplicationContextDirty(any());
        verify(otherTag, never()).markApplicationContextDirty(any());
    }

    @Test
    void Nested内部クラスの終了時には外側と共有するコンテキストを破棄しない() {
        TestContext context = contextFor(TestcontainersFixture.InnerFixture.class, true);

        listener.afterTestClass(context);

        verify(context, never()).markApplicationContextDirty(any());
    }

    @Test
    void afterTestClassではDirtiesContextTestExecutionListenerの直後に実行される順序を持つ() {
        assertThat(listener.getOrder())
                .isEqualTo(new DirtiesContextTestExecutionListener().getOrder() - 1);
    }

    private static TestContext contextFor(Class<?> testClass, boolean loaded) {
        TestContext context = mock(TestContext.class);
        doReturn(testClass).when(context).getTestClass();
        when(context.hasApplicationContext()).thenReturn(loaded);
        return context;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Tag(MYSQL)
    @interface MySqlSuite {
    }

    @Tag(MYSQL)
    private interface MySqlContract {
    }

    @Testcontainers
    private static class TestcontainersFixture {

        @Nested
        @Testcontainers
        @Tag(MYSQL)
        class InnerFixture {
        }
    }

    @Tag(MYSQL)
    private static class MySqlTaggedFixture {
    }

    private static class InheritedTestcontainersFixture extends TestcontainersFixture {
    }

    private static class InheritedTagFixture extends MySqlTaggedFixture {
    }

    @MySqlSuite
    private static class MetaAnnotatedFixture {
    }

    @Tag("h2")
    @Tag(MYSQL)
    private static class RepeatedTagsFixture {
    }

    private static class InterfaceTaggedFixture implements MySqlContract {
    }

    private static class PlainFixture {
    }

    @Tag("h2")
    private static class OtherTagFixture {
    }
}
