package com.journeydigital.rymanresidentapp.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DurationUtils {
    private final long seconds;
    private final int nanos;

    public static final DurationUtils ZERO = new DurationUtils(0, 0);

    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    private static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;

    private final BigInteger BI_NANOS_PER_SECOND = BigInteger.valueOf(1000000000L);

    private static final Pattern PATTERN =
            Pattern.compile("([-+]?)P(?:([-+]?[0-9]+)D)?" +
                            "(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?",
                    Pattern.CASE_INSENSITIVE);


    private DurationUtils(long seconds, int nanos) {
        super();
        this.seconds = seconds;
        this.nanos = nanos;
    }

    private DurationUtils create(BigDecimal seconds) {
        BigInteger nanos = seconds.movePointRight(9).toBigIntegerExact();
        BigInteger[] divRem = nanos.divideAndRemainder(BI_NANOS_PER_SECOND);
        if (divRem[0].bitLength() > 63) {
            throw new ArithmeticException("Exceeds capacity of Duration: " + nanos);
        }
        return ofSeconds(divRem[0].longValue(), divRem[1].intValue());
    }

    private static DurationUtils create(long seconds, int nanoAdjustment) {
        if ((seconds | nanoAdjustment) == 0) {
            return ZERO;
        }
        return new DurationUtils(seconds, nanoAdjustment);
    }

    public long toMillis() {
        long millis = multiplyExact(seconds, 1000);
        millis = addExact(millis, nanos / 1000_000);
        return millis;
    }

    public static DurationUtils parse(CharSequence text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = PATTERN.matcher(text);
        if (matcher.matches()) {
            // check for letter T but no time sections
            if ("T".equals(matcher.group(3)) == false) {
                boolean negate = "-".equals(matcher.group(1));
                String dayMatch = matcher.group(2);
                String hourMatch = matcher.group(4);
                String minuteMatch = matcher.group(5);
                String secondMatch = matcher.group(6);
                String fractionMatch = matcher.group(7);
                if (dayMatch != null || hourMatch != null || minuteMatch != null || secondMatch != null) {
                    try {
                        long daysAsSecs = parseNumber(text, dayMatch, SECONDS_PER_DAY, "days");
                        long hoursAsSecs = parseNumber(text, hourMatch, SECONDS_PER_HOUR, "hours");
                        long minsAsSecs = parseNumber(text, minuteMatch, SECONDS_PER_MINUTE, "minutes");
                        long seconds = parseNumber(text, secondMatch, 1, "seconds");
                        int nanos = parseFraction(text, fractionMatch, seconds < 0 ? -1 : 1);

                        return create(negate, daysAsSecs, hoursAsSecs, minsAsSecs, seconds, nanos);
                    } catch (Exception ex) {
                        throw (DateTimeParseException) new DateTimeParseException("Text cannot be parsed to a Duration: overflow", text, 0).initCause(ex);
                    }
                }
            }
        }

        throw new DateTimeParseException("Text cannot be parsed to a Duration", text, 0);
    }

    private static DurationUtils create(boolean negate, long daysAsSecs, long hoursAsSecs, long minsAsSecs, long secs, int nanos) {
        long seconds = addExact(daysAsSecs, addExact(hoursAsSecs, addExact(minsAsSecs, secs)));
        if (negate) {
            return ofSeconds(seconds, nanos).negated();
        }
        return ofSeconds(seconds, nanos);
    }

    public DurationUtils multipliedBy(long multiplicand) {
        if (multiplicand == 0) {
            return ZERO;
        }
        if (multiplicand == 1) {
            return this;
        }
        return create(toSeconds().multiply(BigDecimal.valueOf(multiplicand)));
    }

    private BigDecimal toSeconds() {
        return BigDecimal.valueOf(seconds).add(BigDecimal.valueOf(nanos, 9));
    }

    public DurationUtils negated() {
        return multipliedBy(-1);
    }

    public static DurationUtils ofSeconds(long seconds, long nanoAdjustment) {
        long secs = addExact(seconds, floorDiv(nanoAdjustment, 1000000000L));
        int nos = (int) floorMod(nanoAdjustment, 1000000000);
        return create(secs, nos);
    }

    public static long floorMod(long x, long y) {
        return x - floorDiv(x, y) * y;
    }

    public static long floorDiv(long x, long y) {
        long r = x / y;
        // if the signs are different and modulo not zero, round down
        if ((x ^ y) < 0 && (r * y != x)) {
            r--;
        }
        return r;
    }

    public static long addExact(long x, long y) {
        long r = x + y;
        // HD 2-12 Overflow iff both arguments have the opposite sign of the result
        if (((x ^ r) & (y ^ r)) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    private static long parseNumber(CharSequence text, String parsed, int multiplier, String errorText) {
        // regex limits to [-+]?[0-9]+
        if (parsed == null) {
            return 0;
        }
        try {
            long val = Long.parseLong(parsed);
            return multiplyExact(val, multiplier);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw (DateTimeParseException) new DateTimeParseException("Text cannot be parsed to a Duration: " + errorText, text, 0).initCause(ex);
        }
    }

    private static int parseFraction(CharSequence text, String parsed, int negate) {
        // regex limits to [0-9]{0,9}
        if (parsed == null || parsed.length() == 0) {
            return 0;
        }
        try {
            parsed = (parsed + "000000000").substring(0, 9);
            return Integer.parseInt(parsed) * negate;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw (DateTimeParseException) new DateTimeParseException("Text cannot be parsed to a Duration: fraction", text, 0).initCause(ex);
        }
    }

    public static long multiplyExact(long x, long y) {
        long r = x * y;
        long ax = Math.abs(x);
        long ay = Math.abs(y);
        if (((ax | ay) >>> 31 != 0)) {
            // Some bits greater than 2^31 that might cause overflow
            // Check the result using the divide operator
            // and check for the special case of Long.MIN_VALUE * -1
            if (((y != 0) && (r / y != x)) ||
                    (x == Long.MIN_VALUE && y == -1)) {
                throw new ArithmeticException("long overflow");
            }
        }
        return r;
    }
}


class DateTimeParseException extends RuntimeException {
    private static final long serialVersionUID = 4304633501674722597L;
    private final String parsedString;
    private final int errorIndex;

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex) {
        super(message);
        this.parsedString = parsedData.toString();
        this.errorIndex = errorIndex;
    }

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex, Throwable cause) {
        super(message, cause);
        this.parsedString = parsedData.toString();
        this.errorIndex = errorIndex;
    }

    public String getParsedString() {
        return this.parsedString;
    }

    public int getErrorIndex() {
        return this.errorIndex;
    }
}