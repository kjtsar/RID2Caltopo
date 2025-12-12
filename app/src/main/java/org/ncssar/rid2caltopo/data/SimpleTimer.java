/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;
import java.util.Locale;

public class SimpleTimer {
    private long  startTimeInMsec;
    public SimpleTimer() { restartTimer();}
    public SimpleTimer(long startTimeInMsec) {this.startTimeInMsec = startTimeInMsec;}
    public void restartTimer() {startTimeInMsec = System.currentTimeMillis();}
    public void setStartTimeInMsec(long startTimeInMsec) {this.startTimeInMsec = startTimeInMsec;}
    public long getStartTimeInMsec() {return startTimeInMsec;}

    public static String DurationAsString(long msecDuration) {
        long hours=0, minutes=0, seconds=0, msecs;
        seconds = msecDuration / 1000;
        msecs = msecDuration % 1000;
        minutes = seconds / 60;
        seconds = seconds % 60;
        hours = minutes / 60;
        minutes = minutes % 60;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, msecs);
    }
    public String durationAsString() {
        return DurationAsString(System.currentTimeMillis() - startTimeInMsec);
    }
}
