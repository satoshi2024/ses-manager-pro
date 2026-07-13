package com.ses;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * すべての結合テストの親クラス。
 * コンテキストのキャッシュを利用し、テスト実行速度を向上させます。
 * H2データベースを使用し、各テストメソッド終了時にトランザクションをロールバックします。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {
    // 共通のMockBeanや、再利用可能な初期化ロジックがあればここに定義します。
}
