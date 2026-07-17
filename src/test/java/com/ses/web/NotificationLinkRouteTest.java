package com.ses.web;

import com.ses.common.constant.NotificationLinks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通知リンク⇔ページルートの整合検証（S3）。
 *
 * {@link NotificationLinks} の全 public static String 定数、およびパラメータ付きリンク
 * （要員詳細・顧客詳細）が、実在のページルート（{@code *PageController} のマッピング）へ
 * 解決されることを検証する。リンク切れ（{@code /invoice/list}・{@code /proposal} のような
 * 未定義ルート）が混入するとハンドラが解決されず赤になる。
 *
 * テンプレートの実レンダリングには依存せず、Spring の {@link RequestMappingHandlerMapping} に
 * ハンドラが登録されているか（＝ルートが存在するか）だけを見る。
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationLinkRouteTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private void assertResolves(String uri) throws Exception {
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        HandlerExecutionChain chain = handlerMapping.getHandler(request);
        assertNotNull(chain, "通知リンク " + uri + " が実在のページルートに解決されません");
    }

    @Test
    void 全ての固定通知リンク定数が実在ルートに解決される() throws Exception {
        for (Field f : NotificationLinks.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                assertResolves((String) f.get(null));
            }
        }
    }

    @Test
    void パラメータ付き通知リンクが実在ルートに解決される() throws Exception {
        assertResolves(NotificationLinks.engineerDetail(1L));
        assertResolves(NotificationLinks.customer(1L));
    }
}
