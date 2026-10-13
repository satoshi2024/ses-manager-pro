package com.ses.privacy.foundation;

import java.util.List;

/** recovery主体へappend capabilityを渡さないwitness read専用境界。 */
interface LatestWitnessReader {
    List<SignedWatermark> history(LatestWitnessPort.Partition partition);
    LatestWitnessPort.Latest latest(LatestWitnessPort.Partition partition);
    default List<SignedWatermark> history(LatestWitnessPort.Partition partition, RecoveryDeadline deadline) {
        deadline.check();
        List<SignedWatermark> result = history(partition);
        deadline.check();
        return result;
    }
    default LatestWitnessPort.Latest latest(LatestWitnessPort.Partition partition, RecoveryDeadline deadline) {
        deadline.check();
        LatestWitnessPort.Latest result = latest(partition);
        deadline.check();
        return result;
    }
}
