package com.ses.common.mybatis;

import com.baomidou.mybatisplus.core.batch.BatchSqlSession;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.executor.BatchResult;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * 追記専用（append-only）の表のmapperが継承する共通基。
 *
 * <p>{@link BaseMapper} から継承する更新・削除・insertOrUpdate の全メソッドを default で override し、SQLを発行せずに
 * {@link UnsupportedOperationException} で拒否する。追記の {@code insert} と読取（{@code select*}）は継承のまま使える。
 * {@link AppendOnlySqlInjector} は本interfaceを継承するmapperへ更新・削除のMappedStatementを注入しないため、mapper proxyを
 * 経由しないstatement IDでの実行（{@code ServiceImpl} の一括メソッド・{@code Db}・{@code SqlHelper}・{@code SqlSession#update}）も
 * 実行時に失敗する。DB側では各表の BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する（H2のreplayにはtriggerを
 * 置かないため、fast testではmapper層の拒否が唯一の防御になる）。</p>
 *
 * <p>本interfaceは {@code @MapperScan("com.ses.mapper")} の範囲外に置く。範囲内に置くとentityを持たないmapper beanとして
 * 登録され、{@code AllMappersSchemaSweepTest} の {@code selectList} が失敗する。継承するmapperは {@link #appendOnlyTable()} を
 * default で実装し、拒否の理由に含める表名を返す。{@link BaseMapper} に更新系メソッドが増えた場合は
 * {@code LifecycleDualConfirmationMapperImmutabilityTest} が失敗するので、ここへoverrideを追加すること。</p>
 *
 * @param <T> entityの型
 */
public interface AppendOnlyMapper<T> extends BaseMapper<T> {

    /**
     * 拒否の理由に含める表名。継承するmapperが default で返す（SQLの文ではない）。
     *
     * @return 追記専用の表名
     */
    String appendOnlyTable();

    /** 追記専用の表への更新・削除を拒否する例外。 */
    private static UnsupportedOperationException appendOnly(String table, String operation) {
        return new UnsupportedOperationException(table + " は追記専用です。" + operation + " は禁止されています。");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(Serializable id) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(Object obj, boolean useFill) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(T entity) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByMap(Map<String, Object> columnMap) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int delete(Wrapper<T> queryWrapper) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteBatchIds(Collection<?> idList) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByIds(Collection<?> idList) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByIds(Collection<?> collections, boolean useFill) {
        throw appendOnly(appendOnlyTable(), "DELETE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int updateById(T entity) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int update(T entity, Wrapper<T> updateWrapper) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int update(Wrapper<T> updateWrapper) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 既存行を更新し得る insertOrUpdate は禁止（追記は insert だけで行う） */
    @Override
    default boolean insertOrUpdate(T entity) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 UPDATE は禁止 */
    @Override
    default List<BatchResult> updateById(Collection<T> entityList) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 UPDATE は禁止 */
    @Override
    default List<BatchResult> updateById(Collection<T> entityList, int batchSize) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<T> entityList) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<T> entityList, int batchSize) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<T> entityList, BiPredicate<BatchSqlSession, T> insertPredicate) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<T> entityList, BiPredicate<BatchSqlSession, T> insertPredicate,
                                             int batchSize) {
        throw appendOnly(appendOnlyTable(), "UPDATE");
    }
}
