package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Argument positions consumed by a successful java.util.Formatter template. */
final class FormatArgumentUsage {
    private static final Pattern SPECIFIER = Pattern.compile("%(?:([0-9]+)\\$)?([-#+ 0,(<]*)([0-9]*)(?:\\.([0-9]+))?([tT])?([a-zA-Z%])");
    private FormatArgumentUsage() { }

    static Set<Integer> indices(String template, boolean formatted) {
        Set<Integer> indices = new LinkedHashSet<>();
        if (!formatted) return indices;
        for (Specifier specifier : specifiers(template)) if (specifier.index >= 0) indices.add(specifier.index);
        return indices;
    }

    static List<Specifier> specifiers(String template) {
        List<Specifier> result = new ArrayList<>();
        Matcher matcher = SPECIFIER.matcher(template);
        int implicit = 0, previous = -1;
        while (matcher.find()) {
            char conversion = matcher.group(6).charAt(0);
            String flags = matcher.group(2), width = matcher.group(3), precision = matcher.group(4), date = matcher.group(5);
            String normalized = "%" + flags.replace("<", "") + width + (precision == null ? "" : "." + precision)
                    + (date == null ? "" : date) + conversion;
            if (date == null && (conversion == '%' || conversion == 'n')) {
                result.add(new Specifier(-1, matcher.start(), matcher.end(), conversion, precision, false, normalized));
                continue;
            }
            int index;
            if (flags.contains("<")) index = previous;
            else if (matcher.group(1) != null) index = Integer.parseInt(matcher.group(1)) - 1;
            else index = implicit++;
            if (index < 0 || index > 8191) throw new IllegalArgumentException("Invalid format argument position");
            previous = index;
            result.add(new Specifier(index, matcher.start(), matcher.end(), conversion, precision, date != null, normalized));
        }
        return result;
    }

    static final class Specifier {
        final int index, start, end;
        final char conversion;
        final Integer precision;
        final boolean date;
        final String normalized;
        Specifier(int index, int start, int end, char conversion, String precision, boolean date, String normalized) {
            this.index = index; this.start = start; this.end = end; this.conversion = conversion;
            this.precision = precision == null ? null : Integer.valueOf(precision);
            this.date = date; this.normalized = normalized;
        }
        boolean potentiallyLossy(Object value) {
            char lower = Character.toLowerCase(conversion);
            return date || lower == 'f' || lower == 'e' || lower == 'g' || lower == 'h'
                    || lower == 'a' && precision != null || lower == 's' && (precision != null || conversion == 'S')
                    || lower == 'b';
        }
        String informationShape() {
            char lower = Character.toLowerCase(conversion);
            int digits = precision == null && (lower == 'f' || lower == 'e' || lower == 'g') ? 6
                    : precision == null ? -1 : precision;
            return (date ? "date:" : "") + conversion + ":" + digits;
        }
    }
}
