package com.cryonum.captusbprint.updates;

import java.net.URI;
import java.net.URISyntaxException;

/** URL authority is checked before OkHttp/browser normalization. No remote URL configuration. */
public final class UpdateUrls {
    public static final String MANIFEST = "https://raw.githubusercontent.com/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json";
    public static final String APPLICATION_ID = "com.cryonum.captusbprint";
    private UpdateUrls() {}

    private static URI parse(String value, String host) {
        if (value == null || value.length() > 300) throw new IllegalArgumentException("URL length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || c >= 127 || c == '\\' || c == '%')
                throw new IllegalArgumentException("Ambiguous URL");
        }
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || !host.equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(host.equals(uri.getRawAuthority()) || (host + ":443").equals(uri.getRawAuthority())))
                throw new IllegalArgumentException("URL authority");
            return uri;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid URL", error);
        }
    }

    public static URI manifest(String value) {
        URI uri = parse(value, "raw.githubusercontent.com");
        if (!"/alcovaibe/capt-usb-print/refs/heads/updates/updates/android.json".equals(uri.getRawPath())) throw new IllegalArgumentException("Manifest path");
        return uri;
    }

    public static String tag(String name) {
        if (name == null || !name.matches("0\\.1\\.0-a2\\.[1-9][0-9]{0,8}"))
            throw new IllegalArgumentException("Version name");
        return "v0.1.0-a2-signed.build." + name.substring(name.lastIndexOf('.') + 1);
    }

    public static URI release(String value) {
        URI uri = parse(value, "github.com");
        if (!uri.getRawPath().matches("/alcovaibe/capt-usb-print/releases/tag/v0\\.1\\.0-a2-signed\\.build\\.[1-9][0-9]{0,8}"))
            throw new IllegalArgumentException("Release path");
        return uri;
    }
}
