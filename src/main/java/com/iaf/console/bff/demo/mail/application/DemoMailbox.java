package com.iaf.console.bff.demo.mail.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.iaf.console.bff.demo.mail.domain.DemoMail;

/** The demo's outgoing mail, in memory, newest first; the oldest go past 500. */
public class DemoMailbox {

    static final int KEPT = 500;

    private final Deque<DemoMail> mails = new ArrayDeque<>();

    public synchronized void add(DemoMail mail) {
        mails.addFirst(mail);
        while (mails.size() > KEPT) {
            mails.removeLast();
        }
    }

    /** The mails, newest first, to one address or (null) to anyone. */
    public synchronized List<DemoMail> list(String to) {
        return mails.stream().filter(m -> to == null || to.isBlank() || to.equalsIgnoreCase(m.to())).toList();
    }
}
