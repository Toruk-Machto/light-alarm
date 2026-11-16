package sg.lightalarm;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.zone.ZoneOffsetTransition;

final class Schedule {
    static final int ONCE = 0, WEEK = 1, MONTH_DAY = 2, MONTH_WEEK = 3;
    int kind = ONCE, minute = 420, monthDay = 1, ordinal = 1, weekday = 1;
    String date = LocalDate.now().plusDays(1).toString(), end = "", zone = "", lastDay = "";
    final int[] week = {420, 420, 420, 420, 420, -1, -1};

    ZoneId zone(ZoneId current) {
        return zone.isEmpty() ? current : ZoneId.of(zone);
    }

    record Next(long at, String day) {}

    Next next(long after, ZoneId current) {
        ZoneId tz = zone(current);
        LocalDate start = Instant.ofEpochMilli(after).atZone(tz).toLocalDate();
        LocalDate until = end.isEmpty() || kind == ONCE ? null : LocalDate.parse(end);
        if (kind == ONCE) return candidate(LocalDate.parse(date), minute, after, tz);
        if (kind == WEEK) {
            for (int offset = 0; offset <= 14; offset++) {
                LocalDate day = start.plusDays(offset);
                if (until != null && day.isAfter(until)) break;
                int time = week[day.getDayOfWeek().getValue() - 1];
                if (time < 0) continue;
                Next result = candidate(day, time, after, tz);
                if (result != null) return result;
            }
            return null;
        }
        for (int offset = 0; offset < 24; offset++) {
            YearMonth month = YearMonth.from(start).plusMonths(offset);
            LocalDate day;
            if (kind == MONTH_DAY) {
                if (monthDay > month.lengthOfMonth()) continue;
                day = month.atDay(monthDay);
            } else {
                DayOfWeek dow = DayOfWeek.of(weekday);
                day = ordinal == -1
                        ? month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(dow))
                        : month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dow));
                if (!YearMonth.from(day).equals(month)) continue;
            }
            if (until != null && day.isAfter(until)) return null;
            Next result = candidate(day, minute, after, tz);
            if (result != null) return result;
        }
        return null;
    }

    private Next candidate(LocalDate day, int minute, long after, ZoneId zone) {
        if (!lastDay.isEmpty() && day.toString().compareTo(lastDay) <= 0) return null;
        LocalDateTime local = day.atStartOfDay().plusMinutes(minute);
        ZoneOffsetTransition transition = zone.getRules().getTransition(local);
        long at = transition != null && transition.isGap()
                ? transition.getInstant().toEpochMilli()
                : local.atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
        return at > after ? new Next(at, day.toString()) : null;
    }
}
