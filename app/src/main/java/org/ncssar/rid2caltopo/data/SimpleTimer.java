package org.ncssar.rid2caltopo.data;
import java.util.Locale;

public class SimpleTimer {
    private long  startTimeInMsec;
    public SimpleTimer() { restartTimer();}
    public SimpleTimer(long startTimeInMsec) {this.startTimeInMsec = startTimeInMsec;}
    public void restartTimer() {startTimeInMsec = System.currentTimeMillis();}
    public long durationInMilliseconds() {return System.currentTimeMillis() - startTimeInMsec;}
    public double durationInSeconds() {return (double)(System.currentTimeMillis() - startTimeInMsec) / 1000.0;}
    public long getStartTimeInMsec() {return startTimeInMsec;}

    public String durationAsString() {
        long hours=0, minutes=0, seconds=0, msecs;
        msecs = System.currentTimeMillis() - startTimeInMsec;
        seconds = msecs / 1000;
        msecs = msecs % 1000;
        minutes = seconds / 60;
        seconds = seconds % 60;
        hours = minutes / 60;
        minutes = minutes % 60;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, msecs);
    }
}
