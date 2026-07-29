package com.ses.service.security;

/** ファイルscannerの判定結果。感染内容やsecretはreasonへ含めない。 */
public record FileScanResult(Status status, String scannerVersion, String reason) {

    public enum Status {
        CLEAN,
        INFECTED,
        UNAVAILABLE
    }

    public static FileScanResult clean(String version) {
        return new FileScanResult(Status.CLEAN, version, null);
    }

    public static FileScanResult infected(String version) {
        return new FileScanResult(Status.INFECTED, version, "malware signature detected");
    }

    public static FileScanResult unavailable(String reason) {
        return new FileScanResult(Status.UNAVAILABLE, null, reason);
    }
}
