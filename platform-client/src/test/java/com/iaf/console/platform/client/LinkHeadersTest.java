package com.iaf.console.platform.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LinkHeadersTest {

    @Test
    void readsTheNextCursor() {
        List<String> link = List.of("<http://platform/api/v1/resources?type=vm&limit=50&after=WyIyMDI2IiwiYSJd>; rel=\"next\"");
        assertThat(LinkHeaders.nextCursor(link)).contains("WyIyMDI2IiwiYSJd");
        assertThat(LinkHeaders.nextUrl(link)).contains("http://platform/api/v1/resources?type=vm&limit=50&after=WyIyMDI2IiwiYSJd");
        assertThat(LinkHeaders.nextCursor(List.of("</api/v1/jobs?after=abc>; rel=\"prev\", </api/v1/jobs?after=def%3D>; rel=\"next\"")))
                .contains("def=");
        assertThat(LinkHeaders.nextCursor(null)).isEmpty();
        assertThat(LinkHeaders.nextCursor(List.of())).isEmpty();
        assertThat(LinkHeaders.nextCursor(List.of("</api/v1/jobs>; rel=\"next\""))).isEmpty();
    }

    @Test
    void writesOne() {
        String header = LinkHeaders.next("/api/v1/resources?type=vm", "a b=");
        assertThat(header).isEqualTo("</api/v1/resources?type=vm&after=a+b%3D>; rel=\"next\"");
        assertThat(LinkHeaders.nextCursor(List.of(header))).contains("a b=");
        assertThat(LinkHeaders.next("/api/v1/jobs", "c")).isEqualTo("</api/v1/jobs?after=c>; rel=\"next\"");
    }
}
