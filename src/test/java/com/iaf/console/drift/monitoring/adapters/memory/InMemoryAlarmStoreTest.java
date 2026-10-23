package com.iaf.console.drift.monitoring.adapters.memory;

import com.iaf.console.drift.monitoring.adapters.AlarmStoreContract;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;

/** The demo's and the tests' alarm store keeps the MongoDB store's rules. */
class InMemoryAlarmStoreTest extends AlarmStoreContract {

    @Override
    protected AlarmStorePort fresh() {
        return new InMemoryAlarmStore();
    }
}
