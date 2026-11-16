package sg.lightalarm;
import java.time.*;
public final class ScheduleTest {
    static final ZoneId SGT = ZoneId.of("Asia/Singapore");
    static long utc(String s) { return Instant.parse(s).toEpochMilli(); }
    static void at(Schedule s, String after, ZoneId zone, String expected) {
        Schedule.Next n = s.next(utc(after), zone);
        if (n == null || n.at() != utc(expected)) throw new AssertionError("Expected " + expected + " got " + n);
    }
    static void absent(Schedule s, String after) {
        if (s.next(utc(after), SGT) != null) throw new AssertionError("Schedule unexpectedly armed");
    }
    @org.junit.Test public void recurrenceTimeZonesAndDaylightSaving() {
        Schedule s = new Schedule();
        s.date = "2026-11-17";
        at(s, "2026-11-16T09:00:00Z", SGT, "2026-11-16T23:00:00Z");
        absent(s, "2026-11-16T23:00:00Z");
        s.zone = "Asia/Singapore";
        at(s, "2026-11-16T09:00:00Z", ZoneId.of("Asia/Tokyo"), "2026-11-16T23:00:00Z");
        s.zone = "";
        at(s, "2026-11-16T09:00:00Z", ZoneId.of("Asia/Tokyo"), "2026-11-16T22:00:00Z");
        s.kind = Schedule.WEEK;
        s.week[1] = 480;
        at(s, "2026-11-16T09:00:00Z", SGT, "2026-11-17T00:00:00Z");
        s.lastDay = "2026-11-17";
        at(s, "2026-11-16T09:00:00Z", SGT, "2026-11-17T23:00:00Z");
        s.lastDay = "";
        s.end = "2026-11-17";
        absent(s, "2026-11-17T00:00:00Z");
        s.end = "";
        s.kind = Schedule.MONTH_DAY;
        s.monthDay = 31;
        at(s, "2027-02-01T00:00:00Z", SGT, "2027-03-30T23:00:00Z");
        s.kind = Schedule.MONTH_WEEK;
        at(s, "2026-11-16T09:00:00Z", SGT, "2026-12-06T23:00:00Z");
        s.ordinal = -1;
        at(s, "2026-11-16T09:00:00Z", SGT, "2026-11-29T23:00:00Z");
        s.kind = Schedule.ONCE;
        s.zone = "America/New_York";
        s.date = "2027-03-14";
        s.minute = 150;
        at(s, "2027-03-13T10:00:00Z", SGT, "2027-03-14T07:00:00Z");
        s.date = "2027-11-07";
        s.minute = 90;
        at(s, "2027-11-06T10:00:00Z", SGT, "2027-11-07T05:30:00Z");
        absent(s, "2027-11-07T05:30:00Z");
    }
}
