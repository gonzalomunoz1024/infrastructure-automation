package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What an assistant writes comes out as plain sentences: no HTML, no Markdown, no hidden characters. */
class PlainTextTest {

    @Test
    void markdownComesOutAsItsWords() {
        String plain = PlainText.of("""
                # The answer

                **Three** VMs *failed* in `prod`: see [the list](https://evil.example/x) and ![a chart](c.png).
                > quoted
                ---
                * web-01
                + web-02
                ```bash
                rm -rf /
                ```
                ~~gone~~""", 4000);
        assertEquals("The answer\n\nThree VMs failed in prod: see the list and a chart.\nquoted\n\n- web-01\n- web-02\n\nrm -rf /\n\ngone", plain,
                "a code block's fences go and leave it a paragraph of its own");
    }

    @Test
    void htmlLosesItsTagsScriptsAndComments() {
        String plain = PlainText.of("<p>Hello <b>there</b></p><script>alert('x')</script><!-- hidden --><style>p{}</style>"
                + "<ul><li>one</li><li>two</li></ul>line<br/>break &lt;img src=x onerror=alert(1)&gt; &amp; more", 4000);
        assertFalse(plain.contains("<"), plain);
        assertFalse(plain.contains("alert('x')"), plain);
        assertFalse(plain.contains("hidden"), plain);
        assertFalse(plain.contains("onerror"), "encoded markup is decoded once and stripped: " + plain);
        assertTrue(plain.startsWith("Hello there"), plain);
        assertTrue(plain.contains("- one\n- two"), plain);
        assertTrue(plain.contains("line\nbreak"), plain);
        assertTrue(plain.endsWith("& more"), plain);
    }

    @Test
    void hiddenAndDirectionChangingCharactersAreDropped() {
        assertEquals("web-01 is fine", PlainText.of("web-01‮ is​ fine\u0007", 100));
        assertEquals("a b", PlainText.of("a  b", 100));
    }

    @Test
    void longTextIsCutAtASpaceAndBlankTextIsNothing() {
        String cut = PlainText.of("one two three four five six seven eight nine ten", 20);
        assertTrue(cut.length() <= 20, cut);
        assertTrue(cut.endsWith("…"), cut);
        assertEquals("one two three four…", cut);
        assertNull(PlainText.of("  \n\n ", 100));
        assertNull(PlainText.of(null, 100));
        assertNull(PlainText.of("<b></b>", 100));
    }

    @Test
    void aLineJoinsItsParagraphs() {
        assertEquals("List the failed VMs in prod", PlainText.line("## List the **failed** VMs\n\nin prod", 200));
        assertEquals("abc…", PlainText.line("abcdefgh", 4));
    }
}
