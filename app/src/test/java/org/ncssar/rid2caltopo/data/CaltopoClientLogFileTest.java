package org.ncssar.rid2caltopo.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CaltopoClientLogFileTest {
    @Test
    public void buildDebugLogFilename_includesTxtSuffixAtCreation() {
        String filename = CaltopoClient.BuildDebugLogFilename(1_777_928_118_000L);

        assertTrue(filename.startsWith("Log_"));
        assertTrue(filename.endsWith(".txt"));
        assertEquals(1, filename.split("\\.txt", -1).length - 1);
    }
}
