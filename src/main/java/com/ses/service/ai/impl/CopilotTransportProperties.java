package com.ses.service.ai.impl;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 外部運用者が別途承認・配布する期限付きnumeric-address snapshot。空が既定。 */
@ConfigurationProperties(prefix = "ai.copilot.transport")
public class CopilotTransportProperties {

    private Map<String, HostSnapshotProperties> hosts = new HashMap<>();

    public Map<String, HostSnapshotProperties> getHosts() {
        return hosts;
    }

    public void setHosts(Map<String, HostSnapshotProperties> hosts) {
        this.hosts = hosts == null ? new HashMap<>() : new HashMap<>(hosts);
    }

    CopilotDnsSnapshotResolver.DnsSnapshot toSnapshot() {
        Map<String, CopilotDnsSnapshotResolver.AddressSet> snapshot = new HashMap<>();
        hosts.forEach((host, config) -> {
            try {
                List<byte[]> bytes = config.getAddressBytesHex().stream()
                        .map(CopilotTransportProperties::parseAddressBytes).toList();
                snapshot.put(host, CopilotDnsSnapshotResolver.AddressSet.fromAddressBytes(
                        host, bytes, Instant.ofEpochMilli(config.getExpiresAtEpochMillis())));
            } catch (UnknownHostException | IllegalArgumentException ex) {
                throw new IllegalStateException("invalid Copilot DNS snapshot configuration", ex);
            }
        });
        return new CopilotDnsSnapshotResolver.DnsSnapshot(snapshot);
    }

    private static byte[] parseAddressBytes(String hex) {
        if (hex == null || (hex.length() != 8 && hex.length() != 32) || !hex.matches("(?i)[0-9a-f]+")) {
            throw new IllegalArgumentException("DNS addresses must be fixed-length hexadecimal bytes");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    public static class HostSnapshotProperties {
        private List<String> addressBytesHex = new ArrayList<>();
        private long expiresAtEpochMillis;

        public List<String> getAddressBytesHex() {
            return addressBytesHex;
        }

        public void setAddressBytesHex(List<String> addressBytesHex) {
            this.addressBytesHex = addressBytesHex == null ? new ArrayList<>() : new ArrayList<>(addressBytesHex);
        }

        public long getExpiresAtEpochMillis() {
            return expiresAtEpochMillis;
        }

        public void setExpiresAtEpochMillis(long expiresAtEpochMillis) {
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }
}
