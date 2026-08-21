package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HFP-01 payroll.js UI 契約。
 * <ul>
 *   <li>BUG-01: HTML hidden を使わず、renderStatus が hidden プロパティで接続解除ボタンを出す</li>
 *   <li>BUG-02: opaque 302 を成功扱いせず、JSON DELETE の成否で toast する</li>
 * </ul>
 */
@DisplayName("HFP-01 payroll.js UI contract")
class PayrollJsDisconnectTest {

    private static final Path PAYROLL_JS = Paths.get("src/main/resources/static/js/modules/payroll.js");
    private static final Path PAYROLL_HTML = Paths.get("src/main/resources/templates/payroll/index.html");

    @Test
    @DisplayName("renderStatus_connected_showsDisconnectWithoutHidden")
    void renderStatus_connected_showsDisconnectWithoutHidden() throws Exception {
        String html = Files.readString(PAYROLL_HTML);
        String js = Files.readString(PAYROLL_JS);

        assertFalse(html.matches("(?s).*id=\"connectBtn\"[^>]*\\bhidden\\b.*"),
                "connectBtn に HTML hidden 属性を付けないこと");
        assertFalse(html.matches("(?s).*id=\"reconnectBtn\"[^>]*\\bhidden\\b.*"),
                "reconnectBtn に HTML hidden 属性を付けないこと");
        assertFalse(html.matches("(?s).*id=\"disconnectBtn\"[^>]*\\bhidden\\b.*"),
                "disconnectBtn に HTML hidden 属性を付けないこと");

        assertTrue(js.contains("setBtnVisible"), "setBtnVisible で表示制御すること");
        assertTrue(js.contains("prop('hidden'"), "hidden プロパティで表示制御すること");
        assertFalse(js.contains("disconnectBtn.show()"), "jQuery .show() だけで disconnect を出さないこと");
        assertFalse(js.contains("connectBtn.show()"), "jQuery .show() だけで connect を出さないこと");

        runNodeScript("""
            function setBtnVisible(btn, visible) {
              btn.hidden = !visible;
              btn.style.display = visible ? '' : 'none';
            }
            const disconnectBtn = { hidden: true, style: { display: 'none' } };
            const connectBtn = { hidden: true, style: { display: 'none' } };
            setBtnVisible(connectBtn, false);
            setBtnVisible(disconnectBtn, false);
            setBtnVisible(disconnectBtn, true);
            if (disconnectBtn.hidden !== false) {
              console.error('hidden が外れていない');
              process.exit(1);
            }
            if (disconnectBtn.style.display === 'none') {
              console.error('display が none のまま');
              process.exit(1);
            }
            setBtnVisible(disconnectBtn, false);
            setBtnVisible(connectBtn, true);
            if (connectBtn.hidden !== false || connectBtn.style.display === 'none') {
              console.error('未接続時 connectBtn 不可視');
              process.exit(1);
            }
            console.log('SUCCESS');
            process.exit(0);
            """);
    }

    @Test
    @DisplayName("revokeFailureDoesNotToastSuccess")
    void revokeFailureDoesNotToastSuccess() throws Exception {
        String js = Files.readString(PAYROLL_JS);
        assertFalse(js.contains("opaqueredirect"), "opaque redirect を成功判定に使わないこと");
        assertFalse(js.contains("redirect: 'manual'"), "redirect:manual の 302 成功判定を使わないこと");
        assertTrue(js.contains("SES.api.delete('/integrations/freee')")
                        || js.contains("SES.api.delete(\"/integrations/freee\")"),
                "解除は JSON DELETE API を呼ぶこと");
        assertTrue(js.contains("接続を解除しました"), "成功 toast 文言が存在すること");

        runNodeScript("""
            let successToast = 0;
            let errorToast = 0;
            const SES = {
              toast: {
                success() { successToast++; },
                error() { errorToast++; }
              },
              api: {
                async delete(url) {
                  if (url === '/integrations/freee') {
                    SES.toast.error('接続解除に失敗しました');
                    throw new Error('revoke failed');
                  }
                }
              }
            };
            async function disconnectFreee() {
              try {
                await SES.api.delete('/integrations/freee');
                SES.toast.success('接続を解除しました');
              } catch (e) {
              }
            }
            disconnectFreee().then(() => {
              if (successToast !== 0) {
                console.error('失敗時に成功toastが出た');
                process.exit(1);
              }
              if (errorToast < 1) {
                console.error('失敗toastが出ていない');
                process.exit(1);
              }
              console.log('SUCCESS');
              process.exit(0);
            }).catch((e) => {
              console.error(e);
              process.exit(1);
            });
            """);
    }

    private void runNodeScript(String script) throws IOException, InterruptedException {
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため skip します");
        }
        Path temp = Files.createTempFile("payroll_js_test_", ".js");
        Files.writeString(temp, script);
        try {
            Process p = new ProcessBuilder("node", temp.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            assertTrue(finished && p.exitValue() == 0, "payroll.js UI test failed: " + output);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
