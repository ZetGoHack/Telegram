package org.telegram.rawgram.calc.time;

import java.time.ZoneId;
import java.time.ZonedDateTime;


public final class TimeZoneOptions {
    private final ZoneId local;
    private final ZonedDateTime now;

    public TimeZoneOptions(ZoneId zoneId, ZonedDateTime zonedDateTime) {
        this.local = zoneId;
        this.now = zonedDateTime;
    }


    public final ZoneId getLocal() {
        return this.local;
    }

    public final ZonedDateTime getNow() {
        return this.now;
    }
}
