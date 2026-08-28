package com.ses.service.certification;

import com.ses.entity.EngineerCertification;

import java.time.LocalDate;
import java.util.Set;

/** 資格recordの状態定数と導出状態。CORRECTEDは保存状態にしない。 */
public final class CertificationRecordStates {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String VERIFIED = "VERIFIED";
    public static final String ACTIVE = "ACTIVE";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final Set<String> TERMINAL = Set.of(CANCELLED, SUPERSEDED, REJECTED);

    private CertificationRecordStates() {
    }

    public static boolean isTerminal(String state) {
        return TERMINAL.contains(state);
    }

    public static boolean isCorrectable(String state) {
        return DRAFT.equals(state) || SUBMITTED.equals(state) || VERIFIED.equals(state)
                || ACTIVE.equals(state);
    }

    /** EXPIREDはcurrent statusへ保存せず、期限日より後の日付から導出する。 */
    public static String effectiveState(EngineerCertification record, LocalDate asOf) {
        if (record == null || record.getRecordState() == null) {
            return null;
        }
        if (ACTIVE.equals(record.getRecordState()) && record.getExpiresOn() != null
                && asOf != null && asOf.isAfter(record.getExpiresOn())) {
            return "EXPIRED";
        }
        return record.getRecordState();
    }
}
