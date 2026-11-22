package com.ses.featureenablement.state;

import lombok.Data;

import java.time.LocalDate;

/**
 * 確認済みの組の member と、その署名の行の参照・日付・値・状態（snapshot の読込みの1行。design §2.6.1 の P2-N1: 組の決定の束縛は
 * member の行の状態に依らず全ての member を入力にする）。氏名・保存名は読まない。
 */
@Data
public class BundleMemberRow {

    private Long bundleId;

    private String kindCode;

    private Long signOffId;

    private String reference;

    private LocalDate signedOn;

    private LocalDate expiresOn;

    private String structuredValues;

    private String status;
}
