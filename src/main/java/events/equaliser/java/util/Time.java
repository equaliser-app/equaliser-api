package events.equaliser.java.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.joda.time.DateTime;

/**
 * Methods related to parsing and formatting times and timezones.
 */
public class Time {

    private static final Map<Long, String> DAYS_LOOKUP =
            IntStream.rangeClosed(1, 31).boxed().collect(Collectors.toMap(Long::valueOf, Time::getOrdinal));

    // 28th February 2017
    private static final DateTimeFormatter FORMAT_DATE_FORMATTER = new DateTimeFormatterBuilder()
                .appendText(ChronoField.DAY_OF_MONTH, DAYS_LOOKUP)
                .appendLiteral(' ')
                .appendText(ChronoField.MONTH_OF_YEAR)
                .appendLiteral(' ')
                .appendValue(ChronoField.YEAR)
                .toFormatter();

    // 28th February 2017 at 7:03pm
    private static final DateTimeFormatter FORMAT_DATETIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendText(ChronoField.DAY_OF_MONTH, DAYS_LOOKUP)
            .appendLiteral(' ')
            .appendText(ChronoField.MONTH_OF_YEAR)
            .appendLiteral(' ')
            .appendValue(ChronoField.YEAR)
            .appendLiteral(" at ")
            .appendValue(ChronoField.CLOCK_HOUR_OF_AMPM, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendText(ChronoField.AMPM_OF_DAY, TextStyle.SHORT)
            .toFormatter();

    private static final String PARSE_BASE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    // http://stackoverflow.com/a/30090987/2765666
    private static final DateTimeFormatter PARSE_OFFSET_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern(PARSE_BASE_PATTERN)
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter();

    public static OffsetDateTime parseOffsetDateTime(String time) {
        if (time == null) {
            // db can quite validly return null
            return null;
        }
        LocalDateTime local = LocalDateTime.parse(time, PARSE_OFFSET_FORMATTER);
        return OffsetDateTime.of(local, ZoneOffset.UTC);
    }

    /**
     * Get a string representation of an OffsetDateTime that Vert.x's MySQL driver understands.
     *
     * @param datetime The datetime object to transform.
     * @return The transformed object.
     */
    public static String toSql(OffsetDateTime datetime) {
        return datetime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String getOrdinal(int n) {
        if (n >= 11 && n <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1:  return n + "st";
            case 2:  return n + "nd";
            case 3:  return n + "rd";
            default: return n + "th";
        }
    }

    public static String formatDate(OffsetDateTime datetime) {
        return datetime.format(FORMAT_DATE_FORMATTER);
    }

    public static String formatDatetime(OffsetDateTime datetime) {
        return datetime.format(FORMAT_DATETIME_FORMATTER);
    }

    public static OffsetDateTime toOffsetDateTime(DateTime datetime) {
        Instant instant = Instant.ofEpochMilli(datetime.getMillis());
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
