package dev.infrai.creator;

import java.util.LinkedHashMap;
import java.util.Map;

final class Json {
    private Json() {}

    static String object(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(string(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return out.append('}').toString();
    }

    static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }

    private static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return string(text);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return object(copy);
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    static Object parse(String source) {
        Parser parser = new Parser(source);
        Object result = parser.readValue();
        parser.space();
        if (!parser.done()) throw new IllegalArgumentException("Trailing JSON content");
        return result;
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) { this.source = source; }
        boolean done() { return index == source.length(); }
        void space() { while (!done() && Character.isWhitespace(source.charAt(index))) index++; }

        Object readValue() {
            space();
            if (done()) throw new IllegalArgumentException("Empty JSON");
            return switch (source.charAt(index)) {
                case '{' -> readObject();
                case '\"' -> readString();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (take('}')) return map;
            do {
                space();
                String key = readString();
                space();
                expect(':');
                map.put(key, readValue());
                space();
            } while (take(','));
            expect('}');
            return map;
        }

        private String readString() {
            expect('\"');
            StringBuilder out = new StringBuilder();
            while (!done()) {
                char c = source.charAt(index++);
                if (c == '\"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (done()) throw new IllegalArgumentException("Incomplete escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '\"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw new IllegalArgumentException("Incomplete unicode escape");
                        out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape");
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        private Object readNumber() {
            int start = index;
            while (!done() && "-+0123456789.eE".indexOf(source.charAt(index)) >= 0) index++;
            if (start == index) throw new IllegalArgumentException("Invalid JSON value");
            String number = source.substring(start, index);
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? Double.parseDouble(number) : Long.parseLong(number);
        }

        private Object literal(String text, Object value) {
            if (!source.startsWith(text, index)) throw new IllegalArgumentException("Invalid JSON literal");
            index += text.length();
            return value;
        }

        private boolean take(char expected) {
            if (!done() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Expected " + expected);
        }
    }
}
