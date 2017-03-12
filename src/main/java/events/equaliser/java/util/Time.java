package events.equaliser.java.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;


public class Time {

    private static final String BASE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    // http://stackoverflow.com/a/30090987/2765666
    private static final DateTimeFormatter FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern(BASE_PATTERN)
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter();

    public static OffsetDateTime parseOffsetDateTime(String time) {
        LocalDateTime local = LocalDateTime.parse(time, FORMATTER);
        return OffsetDateTime.of(local, ZoneOffset.UTC);
    }
}
