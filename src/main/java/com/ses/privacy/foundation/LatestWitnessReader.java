package com.ses.privacy.foundation;

import java.util.List;

/** recovery主体へappend capabilityを渡さないwitness read専用境界。 */
interface LatestWitnessReader {
    List<SignedWatermark> history(LatestWitnessPort.Partition partition);
    LatestWitnessPort.Latest latest(LatestWitnessPort.Partition partition);
}
