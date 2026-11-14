package com.ses.test;

import org.junit.jupiter.api.Tag;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ClassUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * テストクラス単位の Testcontainers に束縛された Spring テストコンテキストを、そのクラスの終了時に破棄する。
 *
 * <p>実 MySQL テストは各クラスが static {@code @Container} と自前の {@code @DynamicPropertySource} を持つ。
 * {@code @DynamicPropertySource} メソッドの集合はコンテキストキャッシュのキーに含まれるため、
 * そのコンテキストが他クラスから再利用されることはない。それでも既定のキャッシュは JVM 終了まで保持し、
 * container 停止後の接続 pool を {@code SpringApplicationShutdownHook} が直列に close する。
 * その時点で Hikari の connection adder は停止済み container の port へ再接続を試みており、
 * pool ごとに数秒待つため、Surefire の fork 終了期限（System.exit 後 30 秒）を超えて dump が出力される。</p>
 *
 * <p>本 listener は {@code @DirtiesContext(classMode = AFTER_CLASS)} と同じ位置でコンテキストを閉じる。
 * 呼出し元は {@code SpringExtension#afterAll} であり、{@code @Container} の停止（クラス単位の
 * {@code ExtensionContext.Store} を閉じる cleanUp 段階）より必ず前に実行されるため、
 * DB へ接続できる状態のまま pool が閉じる。</p>
 *
 * <p>対象判定には JUnit 自身のタグ解決および Testcontainers 自身の注釈探索と同じ {@link AnnotationSupport}
 * を使う（meta-annotation、{@code @Tags}、スーパークラス継承、interface を含む）。
 * {@link Testcontainers} が付いたクラス、またはクラスレベルに JUnit タグ {@value #MYSQL_TAG} を持つクラスが対象で、
 * H2 の fast suite のクラスは対象外のためキャッシュ挙動は変わらない。
 * {@code @Nested} 内部クラスは外側クラスとコンテキストを共有するため対象外とし、外側クラスの終了時に閉じる。</p>
 *
 * <p>{@code META-INF/spring.factories} で既定 listener として登録している。
 * {@code @TestExecutionListeners} を {@code MERGE_WITH_DEFAULTS} なしで宣言したクラスには適用されないため、
 * {@code ContainerBoundContextCloseListenerRegistrationTest} が対象クラス全件で解決されることを検査する。</p>
 */
public class ContainerBoundContextCloseListener extends AbstractTestExecutionListener {

    /**
     * {@code DirtiesContextTestExecutionListener}（3000）より 1 小さい順序。
     * after 系 callback は逆順に呼ばれるため、afterTestClass ではその直後、すなわち
     * AFTER_TEST_CLASS の {@code @Sql} 実行や {@code AfterTestClassEvent} 発行より後に実行される。
     */
    public static final int ORDER = 2999;

    /** CI の mysql-tests gate が選択する JUnit タグ。 */
    static final String MYSQL_TAG = "mysql";

    private static final Logger log = LoggerFactory.getLogger(ContainerBoundContextCloseListener.class);

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        if (!closesContextAfterClass(testClass)) {
            return;
        }
        boolean loaded = testContext.hasApplicationContext();
        // 未ロード（起動失敗・全件skip）の場合、Spring側の破棄はキャッシュに無いキーとして何もしない。
        testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        if (loaded) {
            log.info("container束縛のテストコンテキストをクラス終了時に破棄しました: {}", testClass.getName());
        }
    }

    /**
     * クラス終了時にコンテキストを破棄すべき最上位テストクラスかを判定する。
     *
     * @param testClass 判定対象のテストクラス
     * @return Testcontainers 注釈またはクラスレベルの mysql タグを持つ最上位クラスなら {@code true}
     */
    public static boolean closesContextAfterClass(Class<?> testClass) {
        if (testClass == null || ClassUtils.isInnerClass(testClass)) {
            // JUnit が非 static の内部クラスを実行するのは @Nested の場合だけで、
            // その終了時に閉じると外側クラスがまだ使う共有コンテキストを壊す。
            return false;
        }
        if (AnnotationSupport.isAnnotated(testClass, Testcontainers.class)) {
            return true;
        }
        return AnnotationSupport.findRepeatableAnnotations(testClass, Tag.class).stream()
                .anyMatch(tag -> MYSQL_TAG.equals(tag.value()));
    }
}
