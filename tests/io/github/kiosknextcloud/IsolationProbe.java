package io.github.kiosknextcloud;

/** Triggers the precise Kotlin intrinsic used during Nextcloud URL parsing. */
public final class IsolationProbe {
    public static void main(String[] args) {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.get("https://example.test/photos/");
        if (!url.host().equals("example.test")) throw new AssertionError("URL parsing failed");
        System.out.println("Isolated runtime works with an incompatible host Kotlin.");
    }
}
