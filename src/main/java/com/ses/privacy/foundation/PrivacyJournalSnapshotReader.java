package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;

import java.util.List;

/** recovery主体へappend capabilityを渡さないjournal snapshot read専用境界。 */
interface PrivacyJournalSnapshotReader {
    Snapshot read(JournalPartition partition);

    record Snapshot(JournalHead head, List<JournalEvent> events) {
        Snapshot { events = List.copyOf(events); }
    }
}
