package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.attraction.service.HtmlAttributionParser.Attribution;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HtmlAttributionParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class Parse {

        @Test
        void extractsNameAndUrlFromAnchor() {
            Attribution attr = HtmlAttributionParser.parse(
                    "<a href=\"https://maps.google.com/maps/contrib/12345\">Demo Photographer</a>");

            assertEquals("Demo Photographer", attr.name());
            assertEquals("https://maps.google.com/maps/contrib/12345", attr.url());
        }

        @Test
        void unescapesHtmlEntitiesInBothFields() {
            Attribution attr = HtmlAttributionParser.parse(
                    "<a href=\"https://x.com/?a=1&amp;b=2\">Tom &amp; Jerry</a>");

            assertEquals("Tom & Jerry", attr.name());
            assertEquals("https://x.com/?a=1&b=2", attr.url());
        }

        @Test
        void returnsEmptyForNull() {
            assertSame(Attribution.EMPTY, HtmlAttributionParser.parse(null));
        }

        @Test
        void returnsEmptyForBlankString() {
            assertSame(Attribution.EMPTY, HtmlAttributionParser.parse("   "));
        }

        @Test
        void returnsEmptyWhenNoAnchorTag() {
            assertSame(Attribution.EMPTY, HtmlAttributionParser.parse("just plain text"));
        }

        @Test
        void returnsEmptyWhenAnchorNameIsBlank() {
            // 有 anchor 但 name 是空字串 → 視為無有效 attribution
            assertSame(Attribution.EMPTY,
                    HtmlAttributionParser.parse("<a href=\"https://x.com\"></a>"));
        }

        @Test
        void picksFirstAnchorWhenMultiple() {
            Attribution attr = HtmlAttributionParser.parse(
                    "<a href=\"https://first.com\">First</a> and <a href=\"https://second.com\">Second</a>");

            assertEquals("First", attr.name());
            assertEquals("https://first.com", attr.url());
        }

        @Test
        void handlesExtraAttributesOnAnchor() {
            // Google 偶爾會多帶 target / rel 屬性
            Attribution attr = HtmlAttributionParser.parse(
                    "<a target=\"_blank\" href=\"https://x.com\" rel=\"noopener\">Author</a>");

            assertEquals("Author", attr.name());
            assertEquals("https://x.com", attr.url());
        }
    }

    @Nested
    class FromPhotoNode {

        @Test
        void extractsFirstAttribution() throws Exception {
            JsonNode photo = MAPPER.readTree("""
                    {
                      "html_attributions": [
                        "<a href=\\"https://x.com/a\\">Author A</a>",
                        "<a href=\\"https://x.com/b\\">Author B</a>"
                      ]
                    }
                    """);

            Attribution attr = HtmlAttributionParser.fromPhotoNode(photo);

            assertEquals("Author A", attr.name());
            assertEquals("https://x.com/a", attr.url());
        }

        @Test
        void returnsEmptyWhenNullNode() {
            assertSame(Attribution.EMPTY, HtmlAttributionParser.fromPhotoNode(null));
        }

        @Test
        void returnsEmptyWhenAttributionsArrayMissing() throws Exception {
            JsonNode photo = MAPPER.readTree("{\"photo_reference\": \"abc\"}");
            assertSame(Attribution.EMPTY, HtmlAttributionParser.fromPhotoNode(photo));
        }

        @Test
        void returnsEmptyWhenAttributionsArrayEmpty() throws Exception {
            JsonNode photo = MAPPER.readTree("{\"html_attributions\": []}");
            assertSame(Attribution.EMPTY, HtmlAttributionParser.fromPhotoNode(photo));
        }
    }

    @Nested
    class AttributionRecord {

        @Test
        void emptyHasNullFields() {
            assertNull(Attribution.EMPTY.name());
            assertNull(Attribution.EMPTY.url());
        }

        @Test
        void emptyIsSingleton() {
            // EMPTY 是個 sentinel，多次取用應該是同一個實例
            assertSame(Attribution.EMPTY, Attribution.EMPTY);
            assertNotNull(Attribution.EMPTY);
        }
    }
}
