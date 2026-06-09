package org.ncssar.rid2caltopo.data;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaltopoClientLogFileTest {
    @Test
    public void buildDebugLogFilename_includesTxtSuffixAtCreation() {
        String filename = CaltopoClient.BuildDebugLogFilename(1_777_928_118_000L);

        assertTrue(filename.startsWith("Log_"));
        assertTrue(filename.endsWith(".txt"));
        assertEquals(1, filename.split("\\.txt", -1).length - 1);
    }

    @Test
    public void staleShutdown_doesNotCloseLoggerAfterAppRestart() {
        CloseTrackingOutputStream stream = new CloseTrackingOutputStream();
        CaltopoClient.InstallDebugOutputStreamForTests(stream);
        long staleShutdownGeneration = CaltopoClient.CaptureDebugLogGenerationForTests();

        CaltopoClient.MarkAppActive();
        CaltopoClient.CloseDebugOutputStreamForShutdownForTests(staleShutdownGeneration);

        assertFalse(stream.closed);

        CaltopoClient.CTDebug("CaltopoClientLogFileTest", "after stale shutdown");
        assertTrue(stream.toString().contains("after stale shutdown"));
    }

    @Test
    public void currentShutdown_closesLoggerWhenNoRestartOccurred() {
        CloseTrackingOutputStream stream = new CloseTrackingOutputStream();
        CaltopoClient.InstallDebugOutputStreamForTests(stream);
        long shutdownGeneration = CaltopoClient.CaptureDebugLogGenerationForTests();

        CaltopoClient.CloseDebugOutputStreamForShutdownForTests(shutdownGeneration);

        assertTrue(stream.closed);
    }

    private static final class CloseTrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        boolean closed;

        @Override
        public void write(int b) {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            delegate.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
