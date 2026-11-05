package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Places 回的 html_attributions 是形如
 *   <a href="https://maps.google.com/maps/contrib/...">Author Name</a>
 * 的字串陣列。本工具負責抽出 (name, url)，給 PlaceService / HotelService 共用。
 *
 * 抓不到時兩個欄位都會是 null（呼叫端可直接用，不必額外判 length）。
 */
public final class HtmlAttributionParser {

    private static final Pattern ANCHOR =
            Pattern.compile("<a\\s+[^>]*href=\"([^\"]+)\"[^>]*>(.+?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private HtmlAttributionParser() {}

    public record Attribution(String name, String url) {
        public static final Attribution EMPTY = new Attribution(null, null);
    }

    /** 從 photos[*].html_attributions 取第一筆解析。 */
    public static Attribution fromPhotoNode(JsonNode photoNode) {
        if (photoNode == null) return Attribution.EMPTY;
        JsonNode attrs = photoNode.path("html_attributions");
        if (!attrs.isArray() || attrs.isEmpty()) return Attribution.EMPTY;
        return parse(attrs.get(0).asText(null));
    }

    static Attribution parse(String html) {
        if (html == null || html.isBlank()) return Attribution.EMPTY;
        Matcher m = ANCHOR.matcher(html);
        if (!m.find()) return Attribution.EMPTY;
        String url = unescape(m.group(1));
        String name = unescape(m.group(2)).trim();
        if (name.isEmpty()) return Attribution.EMPTY;
        return new Attribution(name, url);
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
