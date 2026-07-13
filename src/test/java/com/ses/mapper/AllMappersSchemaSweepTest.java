package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 全Mapperを横断的にH2スキーマへ照合する回帰テスト（P8フォローアップ・提案5）。
 *
 * エンティティのフィールド構成と実テーブルの列定義がズレると
 * MyBatis-Plusが自動生成するSELECT文が「Unknown column」で失敗するが、
 * 従来は各機能ごとの個別テストが偶然そのMapperを叩かない限り検出されなかった
 * （実際に本テストの追加時、Menu/SysUser/SystemConfig/AiLog/EmailTemplate の
 * 5マッパー分でH2テストスキーマの不足・不一致が見つかり修正済み）。
 *
 * このテストは `com.ses.mapper` に登録された全 {@link BaseMapper} Bean に対して
 * 引数なしの `selectList(null)` を実行し、例外が発生しないことだけを機械的に検証する。
 * 新しいエンティティ/マッパーを追加した際、このテストが使う
 * `/sql/engineer-schema-h2.sql` に対応テーブルを追加し忘れると即座に赤くなる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class AllMappersSchemaSweepTest {

    @Autowired
    private ApplicationContext applicationContext;

    @TestFactory
    Stream<DynamicTest> allMappersSelectListWithoutSchemaError() {
        Map<String, BaseMapper> mappers = applicationContext.getBeansOfType(BaseMapper.class);

        return mappers.entrySet().stream().map(entry ->
                DynamicTest.dynamicTest(entry.getKey(), () -> {
                    try {
                        entry.getValue().selectList(null);
                    } catch (Exception e) {
                        fail(entry.getKey() + " の selectList(null) がエンティティ/スキーマ不一致で失敗しました: "
                                + e.getMessage(), e);
                    }
                }));
    }
}
