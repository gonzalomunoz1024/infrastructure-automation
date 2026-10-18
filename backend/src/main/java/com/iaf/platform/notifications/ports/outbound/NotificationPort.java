package com.iaf.platform.notifications.ports.outbound;

import com.iaf.platform.notifications.domain.Notification;

import reactor.core.publisher.Mono;

/**
 * One way a notification reaches people. Every adapter gets every notification and delivers it
 * where it can: the in-app inbox always, a Slack-compatible webhook (profile {@code slack}) for the
 * channels it has a webhook for, email (profile {@code mail}) for the addresses it knows. Adapters
 * that block wrap their I/O in {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}.
 */
public interface NotificationPort {

    /** A short name for the delivery record and logs: inbox, slack, email. */
    String name();

    /** True when it was delivered here, false when this adapter has nowhere to deliver it; an error when delivery failed. */
    Mono<Boolean> deliver(Notification notification);
}
