package com.ses.test;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * H2（test）で、MySQL 8の {@code UUID_TO_BIN(text, 0)} / {@code BIN_TO_UUID(bin, 0)} と同じ変換をする関数alias。
 * <p>{@code schema-nf08-dispatch-claim-h2.sql} が {@code CREATE ALIAS} で登録する。swap_flag=0（byte順を入れ替えない）
 * だけを受け付け、結果はRFC 4122のbyte順（Javaの最上位64bit・最下位64bitをbig-endianで並べたもの）である。
 * 本番のmapperはNF08計画 D-2 のとおり小文字36文字表記だけを渡すため、ここでもその表記だけを受け付ける
 * （MySQLは大文字・連字符なしも受け付けるが、testで緩い表記を通して本番との差を作らない）。</p>
 */
public final class H2MySqlUuidFunctions {

    private static final Pattern CANONICAL = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private H2MySqlUuidFunctions() {
    }

    /** MySQL {@code UUID_TO_BIN(text, swapFlag)} 相当。NULLにはNULLを返す。 */
    public static byte[] uuidToBin(String text, int swapFlag) {
        requireNoSwap(swapFlag);
        if (text == null) {
            return null;
        }
        if (!CANONICAL.matcher(text).matches()) {
            throw new IllegalArgumentException("UUIDは小文字36文字表記である必要があります");
        }
        UUID uuid = UUID.fromString(text);
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /** MySQL {@code BIN_TO_UUID(bin, swapFlag)} 相当。小文字36文字表記を返す。NULLにはNULLを返す。 */
    public static String binToUuid(byte[] value, int swapFlag) {
        requireNoSwap(swapFlag);
        if (value == null) {
            return null;
        }
        if (value.length != 16) {
            throw new IllegalArgumentException("UUIDのbinary表現は16 byteである必要があります");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString().toLowerCase(Locale.ROOT);
    }

    private static void requireNoSwap(int swapFlag) {
        if (swapFlag != 0) {
            throw new IllegalArgumentException("swap_flagは0だけを受け付けます");
        }
    }
}
