package com.cryonum.captusbprint.updates;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private static final String RELEASE = "{\"versionCode\":100,\"versionName\":\"0.1.0-a2.120\",\"minSdk\":26,"
            + "\"releaseUrl\":\"https://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120\","
            + "\"notes\":{\"ru\":\"Исправления\\nПодробнее\",\"en\":\"Fixes\"}}";
    private static final String JSON = "{\"schemaVersion\":1,\"platform\":\"android\",\"channel\":\"experimental\","
            + "\"applicationId\":\"com.cryonum.captusbprint\",\"release\":" + RELEASE + "}";
    private UpdateManifest parse(String value) { return UpdateManifest.parse(value.getBytes(StandardCharsets.UTF_8)); }
    private void rejects(String value) { assertThrows(IllegalArgumentException.class, () -> parse(value)); }

    @Test public void comparesNumericCodeAndCompatibility() {
        UpdateManifest.Release release = parse(JSON).release;
        assertTrue(release.isUpdateFor(99, 26));
        assertFalse(release.isUpdateFor(100, 26));
        assertFalse(release.isUpdateFor(101, 37));
        assertFalse(release.isUpdateFor(99, 25));
        assertEquals("Исправления\nПодробнее", release.ru);
    }
    @Test public void emptyReleaseIsExplicit() {
        assertNull(parse(JSON.replace(RELEASE, "null")).release);
    }
    @Test public void rejectsWrongIdentityAndSchema() {
        for (String[] replacement : new String[][]{{"experimental", "internal"}, {"android", "windows"},
                {"com.cryonum.captusbprint", "com.cryonum.captusbprint.internal"}, {"schemaVersion\":1", "schemaVersion\":2"}})
            rejects(JSON.replace(replacement[0], replacement[1]));
    }
    @Test public void rejectsDuplicatesIncludingEscapedKeysAndNullValues() {
        rejects(JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":null,\"schemaVersion\":1"));
        rejects(JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schema\\u0056ersion\":1"));
        rejects(JSON.replace("\"ru\":", "\"ru\":null,\"ru\":"));
    }
    @Test public void rejectsBadNumbersTypesAndUnknownFields() {
        for (String number : new String[]{"0", "-1", "01", "1.0", "1e2", "true", "\"100\"", "2147483648", "999999999999999999999"})
            rejects(JSON.replace("\"versionCode\":100", "\"versionCode\":" + number));
        rejects(JSON.replace("\"minSdk\":26", "\"minSdk\":null"));
        rejects(JSON.replace("\"en\":\"Fixes\"", "\"en\":[]"));
        rejects(JSON.replace("\"platform\":", "\"unknown\":1,\"platform\":"));
    }
    @Test public void rejectsMalformedOversizeAndAmbiguousStrings() {
        for (String value : new String[]{"", JSON + "{}", JSON.replace("\"channel\":", "/*comment*/\"channel\":"),
                JSON.replace("Fixes", "\\uD800"), JSON.replace("Fixes", "\\u202eFixes"),
                JSON.replace("Fixes", "\\u0000"), JSON.replace("Fixes", "x".repeat(1501)),
                JSON.replace("Fixes", "\\u+123"), JSON.replace("\"en\":\"Fixes\"", "\"en\":\"Fixes\","),
                "{".repeat(1000)}) rejects(value);
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(new byte[16385]));
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(new byte[]{(byte) 0xc3, 0x28}));
    }
    @Test public void rejectsVersionUrlMismatch() {
        rejects(JSON.replace("/tag/v0.1.0-a2-signed.build.120", "/tag/v0.1.0-a2-signed.build.121"));
    }
    @Test public void fixedManifestAndReleaseUrlsOnly() {
        assertNotNull(UpdateUrls.manifest(UpdateUrls.MANIFEST));
        assertNotNull(UpdateUrls.manifest("https://raw.githubusercontent.com:443/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json"));
        for (String value : new String[]{
                "http://raw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://raw.githubusercontent.com.evil.example/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://evilraw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://user@raw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://raw.githubusercontent.com@evil.example/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://raw.githubusercontent.com:444/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://raw.githubusercontent.com:0443/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://raw.githubusercontent.com./alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://raw.githubusercontent.com/updates/%61ndroid.json", "https://raw.githubusercontent.com/updates/../alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://raw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json?version=1", "https://raw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json#x",
                "https://raw.githubusercontent.com\\@evil.example/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://raw.githubusercontent.com\n/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json",
                "https://127.0.0.1/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "https://raw.githubusercontent.com/updates/%2561ndroid.json", "javascript:alert(1)",
                "intent://updates", "file:///alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json", "content://updates", "data:application/json,{}"})
            assertThrows(value, IllegalArgumentException.class, () -> UpdateUrls.manifest(value));
        for (String value : new String[]{"https://github.com.evil.example/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120",
                "https://user@github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120",
                "https://github.com/other/Bluetooth-disabler/releases/tag/v0.1.0-a2-signed.build.120",
                "https://github.com/alcovaibe/other/releases/tag/v0.1.0-a2-signed.build.120",
                "https://github.com/alcovaibe/capt-usb-print/releases/latest",
                "https://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120/evil",
                "https://github.com/alcovaibe/capt-usb-print/releases/tag/v1%2e2%2e0",
                "https://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120?x=1",
                "intent://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120"})
            assertThrows(value, IllegalArgumentException.class, () -> UpdateUrls.release(value));
    }
}
