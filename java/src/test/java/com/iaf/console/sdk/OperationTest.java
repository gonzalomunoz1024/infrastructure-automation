package com.iaf.console.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OperationTest {

    @Test
    void theTableHoldsEveryOperationOnceAndTheWritesThatTakeAKey() throws Exception {
        String contract = Files.readString(Path.of("../openapi/console-bff.yaml"));
        Matcher ids = Pattern.compile("(?m)^\\s+operationId:\\s*(\\S+)\\s*$").matcher(contract);
        Set<String> inContract = new HashSet<>();
        while (ids.find()) inContract.add(ids.group(1));

        List<Operation> all = Operation.all();
        Set<String> inTable = new HashSet<>();
        for (Operation op : all) assertTrue(inTable.add(op.operationId), op.operationId + " twice");
        assertEquals(inContract, inTable);

        long refs = Pattern.compile("\\$ref: '#/components/parameters/IdempotencyKey'").matcher(contract).results().count();
        assertEquals(refs, all.stream().filter(op -> op.idempotencyKey).count());
    }

    @Test
    void aRequestIsMatchedToTheMostSpecificOperation() {
        String wfrn = Http.encode("wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1");
        assertEquals("runVerb", Operation.find("POST", "/console/v1/r/vm/" + wfrn + ":stop").operationId);
        assertEquals("previewVerb", Operation.find("POST", "/console/v1/r/vm/" + wfrn + ":stop:preview").operationId);
        assertEquals("createResource", Operation.find("POST", "/console/v1/r/vm").operationId);
        assertEquals("previewCreate", Operation.find("POST", "/console/v1/r/vm:preview").operationId);
        assertEquals("batchCommand", Operation.find("POST", "/console/v1/r/vm:batch").operationId);
        assertEquals("getResource", Operation.find("GET", "/console/v1/r/vm/" + wfrn).operationId);
        assertEquals("cancelJob", Operation.find("POST", "/console/v1/jobs/job-1:cancel").operationId);
        assertEquals("getJob", Operation.find("get", "/console/v1/jobs/job-1").operationId);
        assertNull(Operation.find("POST", "/console/v1/nowhere"));
    }

    @Test
    void theKeyedWritesAreTheContracts() {
        assertTrue(Operation.byId("createResource").idempotencyKey);
        assertTrue(Operation.byId("runVerb").idempotencyKey);
        assertFalse(Operation.byId("previewVerb").idempotencyKey);
        assertFalse(Operation.byId("cancelJob").idempotencyKey);
        assertThrows(IllegalArgumentException.class, () -> Operation.byId("retryJob"));
    }
}
