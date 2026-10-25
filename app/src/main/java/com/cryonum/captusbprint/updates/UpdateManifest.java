package com.cryonum.captusbprint.updates;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small strict schema parser: no lenient Android org.json behavior or unbounded nesting. */
public final class UpdateManifest {
    public static final int MAX_BYTES = 16384;
    public final Release release;
    private UpdateManifest(Release release) { this.release = release; }

    public static final class Release {
        public final int versionCode, minSdk;
        public final String versionName, releaseUrl, ru, en;
        private Release(int code, int sdk, String name, String url, String ru, String en) {
            versionCode = code; minSdk = sdk; versionName = name; releaseUrl = url; this.ru = ru; this.en = en;
        }
        public boolean isUpdateFor(long installedCode, int sdk) {
            return versionCode > installedCode && minSdk <= sdk;
        }
    }

    public static UpdateManifest parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw invalid(); }
        Object root = new Parser(text).parse();
        Map<String, Object> manifest = object(root, "schemaVersion", "platform", "channel", "applicationId", "release");
        if (integer(manifest.get("schemaVersion"), 1, 1) != 1
                || !"android".equals(manifest.get("platform")) || !"experimental".equals(manifest.get("channel"))
                || !UpdateUrls.APPLICATION_ID.equals(manifest.get("applicationId"))) throw invalid();
        if (manifest.get("release") == null) return new UpdateManifest(null);
        Map<String, Object> release = object(manifest.get("release"), "versionCode", "versionName", "minSdk", "releaseUrl", "notes");
        int code = integer(release.get("versionCode"), 1, 2100000000);
        int sdk = integer(release.get("minSdk"), 1, 10000);
        String name = string(release.get("versionName"), 40);
        UpdateUrls.tag(name);
        String url = string(release.get("releaseUrl"), 300);
        if (!UpdateUrls.release(url).getRawPath().equals("/alcovaibe/capt-usb-print/releases/tag/" + UpdateUrls.tag(name))) throw invalid();
        Map<String, Object> notes = object(release.get("notes"), "ru", "en");
        return new UpdateManifest(new Release(code, sdk, name, url, string(notes.get("ru"), 1500), string(notes.get("en"), 1500)));
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid update manifest"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String... keys) {
        if (!(value instanceof Map)) throw invalid();
        Map<String, Object> object = (Map<String, Object>) value;
        if (!object.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw invalid();
        return object;
    }
    private static int integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Long)) throw invalid();
        long number = (Long) value;
        if (number < minimum || number > maximum) throw invalid();
        return (int) number;
    }
    private static String string(Object value, int maximum) {
        if (!(value instanceof String)) throw invalid();
        String text = (String) value;
        if (text.length() > maximum || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) throw invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((Character.isISOControl(c) && c != '\n' && c != '\t')
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return text;
    }

    /** This schema only needs objects, strings, nonnegative integers and null. */
    private static final class Parser {
        private final String text;
        private int position;
        Parser(String text) { this.text = text; }
        Object parse() {
            Object value = value(0); whitespace();
            if (position != text.length()) throw invalid();
            return value;
        }
        private Object value(int depth) {
            whitespace();
            if (depth > 3 || position >= text.length()) throw invalid();
            char c = text.charAt(position);
            if (c == '{') return object(depth);
            if (c == '"') return quoted();
            if (text.startsWith("null", position)) { position += 4; return null; }
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
            String number = text.substring(start, position);
            if (number.isEmpty() || number.length() > 10 || (number.length() > 1 && number.charAt(0) == '0')) throw invalid();
            try { return Long.parseLong(number); } catch (NumberFormatException error) { throw invalid(); }
        }
        private Map<String, Object> object(int depth) {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                String key = quoted();
                whitespace();
                if (!take(':') || result.containsKey(key) || result.size() >= 8) throw invalid();
                result.put(key, value(depth + 1));
                whitespace();
                if (take('}')) return result;
            } while (take(','));
            throw invalid();
        }
        private String quoted() {
            if (!take('"')) throw invalid();
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return result.toString();
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (position == text.length()) throw invalid();
                    char escape = text.charAt(position++);
                    switch (escape) {
                        case '"': case '\\': case '/': c = escape; break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (position + 4 > text.length()) throw invalid();
                            String hex = text.substring(position, position + 4);
                            if (!hex.matches("[0-9a-fA-F]{4}")) throw invalid();
                            c = (char) Integer.parseInt(hex, 16); position += 4; break;
                        default: throw invalid();
                    }
                }
                result.append(c);
                if (result.length() > 2048) throw invalid();
            }
            throw invalid();
        }
        private boolean take(char c) {
            if (position < text.length() && text.charAt(position) == c) { position++; return true; }
            return false;
        }
        private void whitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
        }
    }
}
