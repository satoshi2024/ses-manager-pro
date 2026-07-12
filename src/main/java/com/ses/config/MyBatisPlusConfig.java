package com.ses.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.ses.common.util.SecurityUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 設定クラス
 * ページネーションプラグインと自動フィールド入力ハンドラーを登録する
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * MyBatis-Plus インターセプター設定
     * ページネーション機能を有効化する
     *
     * @return MybatisPlusInterceptor インターセプターインスタンス
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // MySQL用ページネーションプラグインを追加
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 最大ページサイズを制限（パフォーマンス保護）
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }

    /**
     * メタオブジェクトハンドラー
     * エンティティの作成日時・更新日時を自動設定する
     *
     * @return MetaObjectHandler ハンドラーインスタンス
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new AutoFillMetaObjectHandler();
    }

    /**
     * 自動フィールド入力ハンドラー内部クラス
     * INSERT時にcreatedAt・updatedAt・createdByを設定
     * UPDATE時にupdatedAtを設定
     */
    static class AutoFillMetaObjectHandler implements MetaObjectHandler {

        /**
         * INSERT時の自動フィールド入力処理
         * 作成日時・更新日時に現在日時を設定し、登録者IDにログイン中ユーザーを設定する。
         * strictInsertFillは対象フィールド（@TableField(fill=INSERT)）が未設定(null)の場合のみ
         * 値を埋めるため、コントローラー側で明示設定済みの値は上書きしない。
         * バッチ等の非リクエスト文脈ではcurrentUserId()がnullを返すため、その場合は何も設定されない。
         *
         * @param metaObject メタオブジェクト
         */
        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "createdBy", Long.class, SecurityUtils.currentUserId());
        }

        /**
         * UPDATE時の自動フィールド入力処理
         * 更新日時に現在日時を設定する
         *
         * @param metaObject メタオブジェクト
         */
        @Override
        public void updateFill(MetaObject metaObject) {
            this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
