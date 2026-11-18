package com.ses.common.mybatis;

import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.injector.methods.Insert;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import org.apache.ibatis.session.Configuration;

import java.util.List;

/**
 * {@link AppendOnlyMapper} を継承するmapperへ、追記（{@code insert}）と読取（{@code select*}）のMappedStatementだけを注入する
 * SQL injector。それ以外のmapperには {@link DefaultSqlInjector} と同じ文を注入する。
 *
 * <p>mapperのdefault methodによる拒否はmapper proxyを経由する呼出しだけを止める。statement IDを直接実行する経路
 * （{@code ServiceImpl} の一括メソッド・{@code Db}・{@code SqlHelper}・{@code SqlSession#update}）は proxyを通らないため、
 * 更新・削除の文そのものを注入しないことで、実行時に「Mapped Statements collection does not contain value」で失敗させる。</p>
 *
 * <p>残す文は {@link Insert} と、クラス名が {@code Select} で始まる読取の文だけである（許可する側を列挙する）。MyBatis-Plusの
 * 版上げで更新系の文が増えても、追記専用のmapperには注入されない。</p>
 */
public class AppendOnlySqlInjector extends DefaultSqlInjector {

    @Override
    public List<AbstractMethod> getMethodList(Configuration configuration, Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methods = super.getMethodList(configuration, mapperClass, tableInfo);
        if (!AppendOnlyMapper.class.isAssignableFrom(mapperClass)) {
            return methods;
        }
        return methods.stream().filter(AppendOnlySqlInjector::isInsertOrRead).toList();
    }

    /** 追記専用のmapperへ注入してよい文（追記と読取）か。 */
    static boolean isInsertOrRead(AbstractMethod method) {
        return method instanceof Insert || method.getClass().getSimpleName().startsWith("Select");
    }
}
