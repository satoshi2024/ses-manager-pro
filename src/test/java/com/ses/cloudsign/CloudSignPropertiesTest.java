package com.ses.cloudsign;

import com.ses.config.CloudSignProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HFP-02-AC-02-01: 公式host allow-listの検証。
 * prod/sandboxの公式host以外、HTTP、userinfo/query/fragment付きURLを拒否し、
 * enabled=trueの設定不備を起動時fail-closedにする。
 */
class CloudSignPropertiesTest {

    private CloudSignProperties props() {
        CloudSignProperties p = new CloudSignProperties();
        p.setEnvironment("PRODUCTION");
        return p;
    }

    @Test
    void prodは公式hostのみ許可し任意URLを拒否する() {
        CloudSignProperties p = props();
        p.setBaseUrl("https://api.cloudsign.jp");
        assertEquals("https://api.cloudsign.jp", p.effectiveBaseUri().toString());

        for (String bad : new String[]{
                "https://api-sandbox.cloudsign.jp", // 環境不一致
                "http://api.cloudsign.jp",          // HTTP
                "https://evil.example.com",         // 任意host
                "https://api.cloudsign.jp.evil.example.com", // host偽装
                "https://user:pass@api.cloudsign.jp",        // userinfo
                "https://api.cloudsign.jp/path",             // path付き（任意URL化を防ぐ）
                "https://api.cloudsign.jp?q=1",              // query
                "https://api.cloudsign.jp#frag"}) {          // fragment
            p.setBaseUrl(bad);
            assertNull(p.resolveBaseUri(p.resolveEnvironment()), "拒否されるべき: " + bad);
        }
    }

    @Test
    void sandboxは公式hostのみ許可する() {
        CloudSignProperties p = new CloudSignProperties();
        p.setEnvironment("SANDBOX");
        assertEquals("https://api-sandbox.cloudsign.jp", p.effectiveBaseUri().toString());
        p.setBaseUrl("https://api.cloudsign.jp");
        assertNull(p.resolveBaseUri(p.resolveEnvironment()), "sandbox環境でprod hostを拒否");
    }

    @Test
    void enabledなのにclientIdが無ければfailClosed() {
        CloudSignProperties p = props();
        p.setEnabled(true);
        p.setClientId("");
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void enabledでない場合はclientId無しでも起動できる() {
        CloudSignProperties p = props();
        p.setEnabled(false);
        p.setClientId("");
        assertDoesNotThrow(p::validate);
    }

    @Test
    void maxPdfBytesは公式50MBを超えられない() {
        CloudSignProperties p = props();
        p.setEnabled(true);
        p.setClientId("x");
        p.setMaxPdfBytes(51L * 1024 * 1024);
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void 未知のenvironmentは拒否される() {
        CloudSignProperties p = props();
        p.setEnvironment("PROD");
        assertThrows(IllegalStateException.class, p::validate);
    }
}
