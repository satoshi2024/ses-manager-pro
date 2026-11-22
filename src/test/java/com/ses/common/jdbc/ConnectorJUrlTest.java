package com.ses.common.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-007（design.md §12.2）: {@code ImportJdbcReconnectGuard} の入れ子の enum から移した {@link ConnectorJUrl} が、
 * 移す前と同じ分類と展開後の属性を返し、他の package（backup の書込み静止の起動時検査）から使えること。取込の検査の規則そのものは
 * {@code ImportJdbcReconnectGuardTest} が確かめる。
 */
class ConnectorJUrlTest {

    private static final String URL = "jdbc:mysql://db.internal:3306/ses_manager_db?useUnicode=true"
            + "&characterEncoding=utf-8&serverTimezone=Asia/Tokyo";

    @Test
    void 他のpackageから使えるpublicの型である() {
        assertThat(Modifier.isPublic(ConnectorJUrl.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(ConnectorJUrl.Effective.class.getModifiers())).isTrue();
    }

    @Test
    void 接続の型とhostの件数で分類する() {
        assertThat(ConnectorJUrl.inspect(URL, new Properties())).isEqualTo(ConnectorJUrl.SINGLE_HOST);
        assertThat(ConnectorJUrl.inspect("jdbc:mysql://h1:3306,h2:3306/ses", new Properties()))
                .isEqualTo(ConnectorJUrl.MULTIPLE_HOSTS);
        assertThat(ConnectorJUrl.inspect("jdbc:mysql:loadbalance://h1,h2/ses", new Properties()))
                .isEqualTo(ConnectorJUrl.MULTIPLE_HOSTS);
        assertThat(ConnectorJUrl.inspect("jdbc:h2:mem:testdb;MODE=MySQL", new Properties()))
                .isEqualTo(ConnectorJUrl.NOT_CONNECTOR_J);
        // driver の Properties の dnsSrv は単一 host の URL も DNS SRV の failover にする（port 付きの host は解析に失敗する）
        Properties dnsSrv = new Properties();
        dnsSrv.setProperty("dnsSrv", "true");
        assertThat(ConnectorJUrl.inspect("jdbc:mysql://_mysql._tcp.db.example/ses_manager_db", dnsSrv))
                .isEqualTo(ConnectorJUrl.MULTIPLE_HOSTS);
        assertThat(ConnectorJUrl.inspect(URL, dnsSrv)).isEqualTo(ConnectorJUrl.PARSE_FAILED);
    }

    @Test
    void 展開後の属性はURLのqueryとdriverのPropertiesとuseConfigsを含む() {
        Properties info = new Properties();
        info.setProperty("readOnlyPropagatesToServer", "false");

        ConnectorJUrl.Effective fromInfo = ConnectorJUrl.effectiveProperties(URL, info);
        assertThat(fromInfo.connectorJ()).isTrue();
        assertThat(fromInfo.properties()).containsEntry("readOnlyPropagatesToServer", "false")
                .containsEntry("serverTimezone", "Asia/Tokyo");
        assertThat(ConnectorJUrl.effectiveProperties(URL + "&readOnlyPropagatesToServer=false", new Properties())
                .properties()).containsEntry("readOnlyPropagatesToServer", "false");
        // 同梱の設定 file（clusterBase）は解析の中で autoReconnect を足す
        assertThat(ConnectorJUrl.effectiveProperties(URL + "&useConfigs=clusterBase", new Properties()).properties())
                .containsEntry("autoReconnect", "true");
        // 渡した Properties を driver が変えても呼出元へ返さない
        assertThat(info.stringPropertyNames()).containsExactly("readOnlyPropagatesToServer");
    }

    @Test
    void Connector_J以外のURLは対象外で解析できないURLは属性を返さない() {
        ConnectorJUrl.Effective h2 = ConnectorJUrl.effectiveProperties("jdbc:h2:mem:testdb", new Properties());
        assertThat(h2.connectorJ()).isFalse();
        assertThat(h2.properties()).isNull();

        ConnectorJUrl.Effective missing = ConnectorJUrl.effectiveProperties(
                URL + "&propertiesTransform=com.example.NoSuchTransform", new Properties());
        assertThat(missing.connectorJ()).isTrue();
        assertThat(missing.properties()).as("確かめられない（呼出し側が拒否する）").isNull();
    }
}
