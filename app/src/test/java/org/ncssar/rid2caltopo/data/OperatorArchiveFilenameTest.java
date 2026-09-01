package org.ncssar.rid2caltopo.data;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;

public class OperatorArchiveFilenameTest {
    @Test
    public void everyOperatorArchiveUsesAppleLocalizedTimestampContract() {
        long start = Instant.parse("2026-08-31T17:54:45Z").toEpochMilli();
        ZoneId pacific = ZoneId.of("America/Los_Angeles");

        assertEquals(
                "31Aug2026-105445-PDT-0700",
                OperatorArchiveFilename.timestamp(start, pacific));
        assertEquals(
                "Log_31Aug2026-105445-PDT-0700.txt",
                OperatorArchiveFilename.log(start, pacific));
        assertEquals(
                "ARCHIVE01-31Aug2026-105445-PDT-0700.json",
                OperatorArchiveFilename.track("ARCHIVE01", start, pacific));
        assertEquals(
                "ARCHIVE01-31Aug2026-105445-PDT-0700.kmz",
                OperatorArchiveFilename.clueReport("ARCHIVE01", start, pacific));
    }
}
