package com.iaf.console.bff.recent.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.recent.ports.outbound.FavouritesStore;
import com.iaf.console.bff.recent.ports.outbound.RecentStore;

/** Wires the recent pages and the favourites. */
@Configuration(proxyBeanMethods = false)
public class RecentConfiguration {

    @Bean
    public RecentPages recentPages(RecentStore recent, FavouritesStore favourites, Access access, PlatformProperties platform, Clock clock) {
        return new RecentPages(recent, favourites, access, platform.partition(), clock);
    }
}
