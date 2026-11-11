package com.ses.privacy.foundation;

/** 期待revisionまたはcommand/actionの一意性が競合したことを示す。 */
public final class JournalConflictException extends RuntimeException {
    public JournalConflictException(String message) { super(message); }
}
