package com.iaf.console.sdk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One server-sent event of {@code /console/v1/events}: {@code event:} the type, {@code id:} the
 * cursor to resume from ({@code Last-Event-ID}), {@code data:} the event.
 *
 * @param event the type, {@code message} when the stream named none
 * @param data the data lines, joined with newlines
 * @param id the last event id the stream set, or null
 * @param retry the reconnection time in milliseconds the stream asked for, or null
 */
public record ServerSentEvent(String event, String data, String id, Long retry) {

    /** Reads the events from a body's lines as the HTML standard's parser does; closing it closes them. */
    static Stream<ServerSentEvent> parse(Stream<String> lines) {
        Iterator<String> source = lines.iterator();
        Parser parser = new Parser();
        Iterator<ServerSentEvent> events = new Iterator<>() {
            private ServerSentEvent ahead;

            @Override
            public boolean hasNext() {
                // the standard drops an event that the stream ends before its blank line
                while (ahead == null && source.hasNext()) ahead = parser.line(source.next());
                return ahead != null;
            }

            @Override
            public ServerSentEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                ServerSentEvent e = ahead;
                ahead = null;
                return e;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(events, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(lines::close);
    }

    private static final class Parser {
        private boolean first = true;
        private String event = "";
        private final List<String> data = new ArrayList<>();
        private String id;
        private Long retry;

        ServerSentEvent line(String line) {
            if (first) {
                first = false;
                if (line.startsWith("﻿")) line = line.substring(1);
            }
            if (line.isEmpty()) {
                ServerSentEvent dispatched = data.isEmpty() ? null
                        : new ServerSentEvent(event.isEmpty() ? "message" : event, String.join("\n", data), id, retry);
                event = "";
                data.clear();
                retry = null;
                return dispatched;
            }
            if (line.startsWith(":")) return null;
            int colon = line.indexOf(':');
            String name = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (name) {
                case "event" -> event = value;
                case "data" -> data.add(value);
                case "id" -> {
                    if (value.indexOf('\0') < 0) id = value;
                }
                case "retry" -> {
                    if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                        try {
                            retry = Long.parseLong(value);
                        } catch (NumberFormatException tooLong) {
                            // ignored, as the standard ignores a value it cannot read
                        }
                    }
                }
                default -> {
                    // the standard ignores other fields
                }
            }
            return null;
        }
    }
}
