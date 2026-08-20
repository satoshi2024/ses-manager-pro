package com.ses.service.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * project.workLocation を都道府県/市区町村まで残す（G10 grain、R1-P2-01）。
 * 番地・建物は落とす。正規化不能なら null（送らない）。
 */
public final class WorkLocationNormalizer {

    private static final Pattern PREF_REST = Pattern.compile("^(.+?[都道府県])(.*)$");
    private static final Pattern CITY = Pattern.compile("^(.+?[市区町村])");
    private static final Pattern BANCHI = Pattern.compile("[0-9０-９]+\\s*[-−ーｰ丁目番号]|[-−][0-9０-９]");

    private WorkLocationNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        Matcher prefMatcher = PREF_REST.matcher(value);
        if (!prefMatcher.matches()) {
            return null;
        }
        String prefecture = prefMatcher.group(1);
        String rest = prefMatcher.group(2) == null ? "" : prefMatcher.group(2);
        if (rest.isBlank()) {
            return prefecture;
        }
        Matcher cityMatcher = CITY.matcher(rest);
        if (!cityMatcher.find()) {
            if (BANCHI.matcher(rest).find()) {
                return prefecture;
            }
            return null;
        }
        return prefecture + cityMatcher.group(1);
    }
}
