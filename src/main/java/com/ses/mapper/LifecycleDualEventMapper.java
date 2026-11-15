package com.ses.mapper;

import com.baomidou.mybatisplus.core.batch.BatchSqlSession;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleDualEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.executor.BatchResult;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * 二者確認の追記監査マッパー。
 *
 * <p>{@code t_lifecycle_dual_event} は追記専用（V195）。訂正が必要なら新しいeventを追記する。
 * UPDATE・DELETE（{@link BaseMapper} から継承した更新・削除・insertOrUpdateの全メソッド）は、SQLを発行せずに
 * {@link UnsupportedOperationException} で拒否する（{@code LifecycleEventMapper} と同じ形。review-ledger LC-P1-15）。
 * DBでも BEFORE UPDATE / BEFORE DELETE trigger が同じ操作を拒否する（H2のreplayにはtriggerを置かないため、
 * fast testではこのmapper層の拒否が唯一の防御になる）。BaseMapperに更新系メソッドが増えた場合は
 * {@code LifecycleDualConfirmationMapperImmutabilityTest} が失敗するので、ここへoverrideを追加すること。</p>
 */
@Mapper
public interface LifecycleDualEventMapper extends BaseMapper<LifecycleDualEvent> {

    /** 追記専用の表への更新・削除を拒否する例外。 */
    private static UnsupportedOperationException appendOnly(String operation) {
        return new UnsupportedOperationException("t_lifecycle_dual_event は追記専用です。" + operation + " は禁止されています。");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(Serializable id) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(Object obj, boolean useFill) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteById(LifecycleDualEvent entity) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByMap(Map<String, Object> columnMap) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int delete(Wrapper<LifecycleDualEvent> queryWrapper) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteBatchIds(Collection<?> idList) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByIds(Collection<?> idList) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: DELETE は禁止 */
    @Override
    default int deleteByIds(Collection<?> collections, boolean useFill) {
        throw appendOnly("DELETE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int updateById(LifecycleDualEvent entity) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int update(LifecycleDualEvent entity, Wrapper<LifecycleDualEvent> updateWrapper) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: UPDATE は禁止 */
    @Override
    default int update(Wrapper<LifecycleDualEvent> updateWrapper) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 既存行を更新し得る insertOrUpdate は禁止（追記は insert だけで行う） */
    @Override
    default boolean insertOrUpdate(LifecycleDualEvent entity) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 UPDATE は禁止 */
    @Override
    default List<BatchResult> updateById(Collection<LifecycleDualEvent> entityList) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 UPDATE は禁止 */
    @Override
    default List<BatchResult> updateById(Collection<LifecycleDualEvent> entityList, int batchSize) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<LifecycleDualEvent> entityList) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<LifecycleDualEvent> entityList, int batchSize) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<LifecycleDualEvent> entityList,
                                             BiPredicate<BatchSqlSession, LifecycleDualEvent> insertPredicate) {
        throw appendOnly("UPDATE");
    }

    /** 追記専用: 一括 insertOrUpdate は禁止 */
    @Override
    default List<BatchResult> insertOrUpdate(Collection<LifecycleDualEvent> entityList,
                                             BiPredicate<BatchSqlSession, LifecycleDualEvent> insertPredicate, int batchSize) {
        throw appendOnly("UPDATE");
    }
}
