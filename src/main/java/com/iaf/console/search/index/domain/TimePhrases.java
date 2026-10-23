package com.iaf.console.search.index.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * English times read as the values of {@code created:} and {@code updated:} terms
 * ({@link TimeRange}), and those values read back as English, for {@link PlainEnglish}. Days are
 * UTC days, a week starts on Monday, and counting back from now a month is 30 days and a year 365.
 * A day without a year is the last one that has passed.
 */
final class TimePhrases {

    /** A time read from the words: how many words it took, the term's value, and how it reads. */
    record Match(int words, String value, String phrase) {
    }

    private record Amount(int words, int n, Unit unit) {
    }

    private record Day(int words, LocalDate date) {
    }

    private enum Unit {
        MINUTE("m", 1, "minute"), HOUR("h", 1, "hour"), DAY("d", 1, "day"), WEEK("w", 1, "week"), MONTH("d", 30, "month"), YEAR("d", 365, "year");

        final String suffix;
        final int factor;
        final String word;

        Unit(String suffix, int factor, String word) {
            this.suffix = suffix;
            this.factor = factor;
            this.word = word;
        }
    }

    private static final Pattern ISO_DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DAY_OF_MONTH = Pattern.compile("^(\\d{1,2})(st|nd|rd|th)?$");
    private static final Pattern YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,4}$");
    private static final Pattern AGE = Pattern.compile("^(\\d{1,4})([mhdw])$");
    private static final int MAX_AGE = 9999;

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2),
            Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("fifteen", 15),
            Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("sixty", 60), Map.entry("ninety", 90));
    private static final Map<String, Unit> UNITS = Map.ofEntries(Map.entry("minute", Unit.MINUTE), Map.entry("minutes", Unit.MINUTE),
            Map.entry("min", Unit.MINUTE), Map.entry("mins", Unit.MINUTE), Map.entry("hour", Unit.HOUR), Map.entry("hours", Unit.HOUR),
            Map.entry("hr", Unit.HOUR), Map.entry("hrs", Unit.HOUR), Map.entry("day", Unit.DAY), Map.entry("days", Unit.DAY),
            Map.entry("week", Unit.WEEK), Map.entry("weeks", Unit.WEEK), Map.entry("wk", Unit.WEEK), Map.entry("wks", Unit.WEEK),
            Map.entry("month", Unit.MONTH), Map.entry("months", Unit.MONTH), Map.entry("year", Unit.YEAR), Map.entry("years", Unit.YEAR),
            Map.entry("yr", Unit.YEAR), Map.entry("yrs", Unit.YEAR));
    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("january", 1), Map.entry("jan", 1), Map.entry("february", 2),
            Map.entry("feb", 2), Map.entry("march", 3), Map.entry("mar", 3), Map.entry("april", 4), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("june", 6), Map.entry("jun", 6), Map.entry("july", 7), Map.entry("jul", 7), Map.entry("august", 8), Map.entry("aug", 8),
            Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("october", 10), Map.entry("oct", 10),
            Map.entry("november", 11), Map.entry("nov", 11), Map.entry("december", 12), Map.entry("dec", 12));
    private static final Set<String> LAST = Set.of("last", "past", "previous");
    private static final Set<String> SPAN_LEADS = Set.of("in", "within", "over", "during", "for");

    private TimePhrases() {
    }

    /** The time the words at {@code i} name, or null when they name none. */
    static Match at(List<String> w, int i, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (word(w, i).isEmpty()) {
            return null;
        }
        Match m;
        if ((m = named(w, i, today)) != null || (m = span(w, i)) != null || (m = ago(w, i)) != null || (m = anchored(w, i, today)) != null) {
            return m;
        }
        Day day = day(w, i, today);
        return day == null ? null : new Match(day.words(), day.date().toString(), "on " + day.date());
    }

    /** today, yesterday, this week, this month, this year. */
    private static Match named(List<String> w, int i, LocalDate today) {
        String a = word(w, i);
        if ("today".equals(a)) {
            return new Match(1, today.toString(), "today (" + today + ")");
        }
        if ("yesterday".equals(a)) {
            return new Match(1, today.minusDays(1).toString(), "yesterday (" + today.minusDays(1) + ")");
        }
        if ("this".equals(a)) {
            LocalDate since = switch (word(w, i + 1)) {
                case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case "month" -> today.withDayOfMonth(1);
                case "year" -> today.withDayOfYear(1);
                default -> null;
            };
            if (since != null) {
                return new Match(2, ">=" + since, "this " + word(w, i + 1) + " (since " + since + ")");
            }
        }
        return null;
    }

    /** [in|within|over|during|for] [the] last|past|previous [N] unit, or within, for or in N unit: since then. */
    private static Match span(List<String> w, int i) {
        int j = i;
        String lead = word(w, j);
        if (SPAN_LEADS.contains(lead)) {
            j++;
        }
        if ("the".equals(word(w, j))) {
            j++;
        }
        if (LAST.contains(word(w, j))) {
            j++;
            Amount amount = amount(w, j, true);
            return amount == null ? null : age(j + amount.words() - i, amount, "");
        }
        if (j > i && ("within".equals(lead) || "for".equals(lead) || "in".equals(lead))) {
            // "within 3 days", "not updated for 30 days", "not updated in 30 days"
            Amount amount = amount(w, j, false);
            return amount == null ? null : age(j + amount.words() - i, amount, "");
        }
        return null;
    }

    /**
     * N unit ago (since then), more than / over / older than N unit [ago|old] (before then),
     * less than / under / newer than N unit [ago|old] (since then).
     */
    private static Match ago(List<String> w, int i) {
        String a = word(w, i);
        String b = word(w, i + 1);
        int j = i;
        String direction = "";
        if (("more".equals(a) || "older".equals(a)) && "than".equals(b)) {
            direction = "<";
            j += 2;
        } else if ("over".equals(a)) {
            direction = "<";
            j += 1;
        } else if (("less".equals(a) || "newer".equals(a) || "fewer".equals(a) || "younger".equals(a)) && "than".equals(b)) {
            j += 2;
        } else if ("under".equals(a)) {
            j += 1;
        }
        Amount amount = amount(w, j, false);
        if (amount == null) {
            return null;
        }
        int end = j + amount.words();
        String tail = word(w, end);
        boolean closed = "ago".equals(tail) || "old".equals(tail);
        if (j == i && !"ago".equals(tail)) {
            // a bare amount is not a time: "two vms"
            return null;
        }
        if (j > i && !closed && !("older".equals(a) || "newer".equals(a) || "younger".equals(a))) {
            // "more than 3 days" without ago is a duration, not a time
            return null;
        }
        return age(end + (closed ? 1 : 0) - i, amount, direction);
    }

    /** since, after, before, until, on, between … and …, from … to …, in or during a month. */
    private static Match anchored(List<String> w, int i, LocalDate today) {
        String a = word(w, i);
        int j = i + 1;
        if ("prior".equals(a) && "to".equals(word(w, i + 1))) {
            j = i + 2;
            a = "before";
        }
        switch (a) {
            case "since", "after", "before", "until", "till", "on", "from" -> {
                String op = switch (a) {
                    case "since", "from" -> ">=";
                    case "after" -> ">";
                    case "before", "until", "till" -> "<";
                    default -> "";
                };
                Day day = day(w, j, today);
                if (day != null) {
                    int end = j + day.words();
                    if ("from".equals(a) && Set.of("to", "until", "till", "through", "thru").contains(word(w, end))) {
                        Day to = day(w, end + 1, today);
                        if (to != null) {
                            return between(day.date(), to.date(), end + 1 + to.words() - i);
                        }
                    }
                    String value = op + day.date();
                    return new Match(end - i, value, describe(value));
                }
                if (!"on".equals(a) && !"from".equals(a)) {
                    Amount amount = amount(w, j, false);
                    if (amount != null && "ago".equals(word(w, j + amount.words()))) {
                        String direction = "<".equals(op) ? "<" : "";
                        return age(j + amount.words() + 1 - i, amount, direction);
                    }
                }
                return null;
            }
            case "between" -> {
                Day from = day(w, j, today);
                if (from != null && "and".equals(word(w, j + from.words()))) {
                    Day to = day(w, j + from.words() + 1, today);
                    if (to != null) {
                        return between(from.date(), to.date(), j + from.words() + 1 + to.words() - i);
                    }
                }
                return null;
            }
            case "in", "during" -> {
                Integer month = MONTHS.get(word(w, j));
                if (month == null || "may".equals(word(w, j)) && !YEAR.matcher(word(w, j + 1)).matches()) {
                    // "in may" is too often the verb
                    return null;
                }
                int words = j + 1 - i;
                YearMonth ym;
                String year = word(w, j + 1);
                if (YEAR.matcher(year).matches()) {
                    ym = YearMonth.of(Integer.parseInt(year), month);
                    words++;
                } else {
                    ym = YearMonth.of(today.getYear(), month);
                    if (ym.atDay(1).isAfter(today)) {
                        ym = ym.minusYears(1);
                    }
                }
                return between(ym.atDay(1), ym.atEndOfMonth(), words);
            }
            default -> {
                return null;
            }
        }
    }

    private static Match between(LocalDate a, LocalDate b, int words) {
        LocalDate from = a.isAfter(b) ? b : a;
        LocalDate to = a.isAfter(b) ? a : b;
        String value = from + ".." + to;
        return new Match(words, value, describe(value));
    }

    private static Match age(int words, Amount amount, String direction) {
        long n = (long) amount.n() * amount.unit().factor;
        if (n < 1 || n > MAX_AGE) {
            return null;
        }
        String value = direction + n + amount.unit().suffix;
        return new Match(words, value, describe(value));
    }

    /** N unit, or the unit alone when {@code one} allows it ("last week"), or a compact age ("7d"). */
    private static Amount amount(List<String> w, int j, boolean one) {
        String a = word(w, j);
        Matcher compact = AGE.matcher(a);
        if (compact.matches()) {
            Unit unit = switch (compact.group(2)) {
                case "m" -> Unit.MINUTE;
                case "h" -> Unit.HOUR;
                case "d" -> Unit.DAY;
                default -> Unit.WEEK;
            };
            return new Amount(1, Integer.parseInt(compact.group(1)), unit);
        }
        Integer n = DIGITS.matcher(a).matches() ? Integer.valueOf(a) : NUMBERS.get(a);
        if (n != null) {
            Unit unit = UNITS.get(word(w, j + 1));
            return unit == null ? null : new Amount(2, n, unit);
        }
        Unit unit = UNITS.get(a);
        return unit != null && one ? new Amount(1, 1, unit) : null;
    }

    /** A day: 2026-10-01, October 1[st] [2026], 1 October [2026], today, yesterday. */
    private static Day day(List<String> w, int j, LocalDate today) {
        String a = word(w, j);
        if ("today".equals(a)) {
            return new Day(1, today);
        }
        if ("yesterday".equals(a)) {
            return new Day(1, today.minusDays(1));
        }
        if (ISO_DAY.matcher(a).matches()) {
            try {
                return new Day(1, LocalDate.parse(a));
            } catch (DateTimeException e) {
                return null;
            }
        }
        Integer month = MONTHS.get(a);
        Integer dayOfMonth = dayOfMonth(word(w, j + 1));
        if (month != null && dayOfMonth != null) {
            return dated(w, j + 2, month, dayOfMonth, 2, today);
        }
        dayOfMonth = dayOfMonth(a);
        month = MONTHS.get(word(w, j + 1));
        if (dayOfMonth != null && month != null) {
            return dated(w, j + 2, month, dayOfMonth, 2, today);
        }
        return null;
    }

    private static Day dated(List<String> w, int yearAt, int month, int dayOfMonth, int words, LocalDate today) {
        String year = word(w, yearAt);
        try {
            if (YEAR.matcher(year).matches()) {
                return new Day(words + 1, LocalDate.of(Integer.parseInt(year), month, dayOfMonth));
            }
            LocalDate date = LocalDate.of(today.getYear(), month, dayOfMonth);
            return new Day(words, date.isAfter(today) ? date.minusYears(1) : date);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Integer dayOfMonth(String s) {
        Matcher m = DAY_OF_MONTH.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int d = Integer.parseInt(m.group(1));
        return d >= 1 && d <= 31 ? d : null;
    }

    /**
     * The opposite of a time as one value, when it is one span: not in the last 30 days is more
     * than 30 days ago, not since a day is before it; null for a day or a range, whose opposite is
     * two spans.
     */
    static String negate(String value) {
        if (value.contains("..")) {
            return null;
        }
        if (value.startsWith(">=")) {
            return "<" + value.substring(2);
        }
        if (value.startsWith(">")) {
            return "<=" + value.substring(1);
        }
        if (value.startsWith("<=")) {
            return ">" + value.substring(2);
        }
        if (value.startsWith("<")) {
            return ">=" + value.substring(1);
        }
        return AGE.matcher(value).matches() ? "<" + value : null;
    }

    /** A term's value as English: {@code 7d} is "in the last 7 days", {@code <2026-10-01} "before 2026-10-01". */
    static String describe(String value) {
        int dots = value.indexOf("..");
        if (dots >= 0) {
            return "between " + point(value.substring(0, dots)) + " and " + point(value.substring(dots + 2));
        }
        if (value.startsWith(">=")) {
            String rest = value.substring(2);
            return AGE.matcher(rest).matches() ? lastly(rest) : "since " + point(rest);
        }
        if (value.startsWith(">")) {
            String rest = value.substring(1);
            return AGE.matcher(rest).matches() ? "less than " + amountOf(rest) + " ago" : "after " + point(rest);
        }
        if (value.startsWith("<=")) {
            String rest = value.substring(2);
            return AGE.matcher(rest).matches() ? amountOf(rest) + " ago or earlier" : "on or before " + point(rest);
        }
        if (value.startsWith("<")) {
            String rest = value.substring(1);
            return AGE.matcher(rest).matches() ? "more than " + amountOf(rest) + " ago" : "before " + point(rest);
        }
        if (AGE.matcher(value).matches()) {
            return lastly(value);
        }
        return "on " + value;
    }

    private static String point(String s) {
        return AGE.matcher(s).matches() ? amountOf(s) + " ago" : s;
    }

    /** "in the last 7 days", "in the last hour". */
    private static String lastly(String age) {
        Matcher m = AGE.matcher(age);
        m.matches();
        return "1".equals(m.group(1)) ? "in the last " + unitWord(m.group(2)) : "in the last " + amountOf(age);
    }

    /** "7 days", "1 hour". */
    private static String amountOf(String age) {
        Matcher m = AGE.matcher(age);
        m.matches();
        String unit = unitWord(m.group(2));
        return m.group(1) + " " + ("1".equals(m.group(1)) ? unit : unit + "s");
    }

    private static String unitWord(String suffix) {
        return switch (suffix) {
            case "m" -> "minute";
            case "h" -> "hour";
            case "d" -> "day";
            default -> "week";
        };
    }

    /** The word at a position, empty past either end, so every lookup simply misses. */
    private static String word(List<String> w, int i) {
        return i >= 0 && i < w.size() ? w.get(i) : "";
    }
}
