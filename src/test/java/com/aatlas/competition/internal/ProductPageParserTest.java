package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductPageParserTest {

    @Test
    void jsonLdProductWithAnOfferObject() {
        String html = """
                <html><head><title>Ball Valve | Store</title>
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"Product",
                  "name":"1/2 in PVC Ball Valve","offers":{"@type":"Offer","price":"6.48","priceCurrency":"USD"}}</script>
                </head></html>""";
        ProductPageParser.Priced p = ProductPageParser.parse(html);
        assertThat(p.price()).isEqualByComparingTo("6.48");
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.title()).isEqualTo("1/2 in PVC Ball Valve");
        assertThat(p.how()).isEqualTo("json-ld");
    }

    @Test
    void jsonLdInAGraphWithAnAggregateOfferAndABrokenBlockFirst() {
        String html = """
                <script type="application/ld+json">{ not json </script>
                <script type='application/ld+json'>{"@graph":[{"@type":"BreadcrumbList"},
                  {"@type":["Product"],"name":"Valve","offers":[{"@type":"AggregateOffer","lowPrice":7.1,"priceCurrency":"usd"}]}]}</script>
                """;
        ProductPageParser.Priced p = ProductPageParser.parse(html);
        assertThat(p.price()).isEqualByComparingTo("7.10");
        assertThat(p.currency()).isEqualTo("USD");
    }

    @Test
    void metaTagsWhenThereIsNoJsonLd() {
        String html = """
                <meta property="og:title" content="PVC Valve 1/2&quot;">
                <meta property="product:price:amount" content="1,204.50">
                <meta property="product:price:currency" content="USD">""";
        ProductPageParser.Priced p = ProductPageParser.parse(html);
        assertThat(p.price()).isEqualByComparingTo("1204.50");
        assertThat(p.how()).isEqualTo("meta");
    }

    @Test
    void microdataAsTheLastResort() {
        String html = "<title>Valve</title><span itemprop=\"price\" content=\"5.95\">$5.95</span>";
        ProductPageParser.Priced p = ProductPageParser.parse(html);
        assertThat(p.price()).isEqualByComparingTo("5.95");
        assertThat(p.title()).isEqualTo("Valve");
        assertThat(p.how()).isEqualTo("microdata");
    }

    @Test
    void visibleTextAloneIsNotAPrice() {
        assertThat(ProductPageParser.parse("<p>Free shipping over $49.00</p>")).isNull();
        assertThat(ProductPageParser.parse(null)).isNull();
    }
}
