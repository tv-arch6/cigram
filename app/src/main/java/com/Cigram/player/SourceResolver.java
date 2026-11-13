package com.Cigram.player;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SourceResolver {

    // يبحث عن file: "..." أو src: '...' أو url="..." ويلتقط رابط m3u8/mp4.
    // تم استبدال lookbehind (غير مسموح بطول غير محدود) بمجموعة التقاط، وتم تهريب علامة " بشكل صحيح.
    private static final Pattern MEDIA_PATTERN = Pattern.compile(
            "(?:file|src|url)\\s*[:=]\\s*['\"](http[^'\"]*?\\.(?:m3u8|mp4)[^'\"]*)['\"]");

    public static String extractDirectVideoUrl(String embedUrl) throws IOException {
        Document document = Jsoup.connect(embedUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .timeout(15000)
                .get();

        // الحالة 1: وسم <video> أو <source> يحتوي على رابط مباشر
        Elements videoSources = document.select("video source, video[src]");
        for (Element source : videoSources) {
            String src = source.hasAttr("src") ? source.absUrl("src") : "";
            if (src.isEmpty()) src = source.attr("src");
            if (src.contains(".mp4") || src.contains(".m3u8")) {
                return src;
            }
        }

        // الحالة 2: البحث داخل وسوم <script>
        for (Element script : document.select("script")) {
            Matcher m = MEDIA_PATTERN.matcher(script.html());
            if (m.find()) {
                return m.group(1);
            }
        }

        // الحالة 3: البحث في كامل نص الصفحة
        Matcher matcher = MEDIA_PATTERN.matcher(document.html());
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null; // لم يتم العثور على رابط مباشر
    }
}
