package com.iaf.console.bff.live.application;

import com.iaf.console.platform.model.FeedEvent;

/** Something else that wants every event of the platform's feed as the relay sees it, in the feed's order. */
public interface FeedListener {

    void onFeedEvent(FeedEvent event);
}
