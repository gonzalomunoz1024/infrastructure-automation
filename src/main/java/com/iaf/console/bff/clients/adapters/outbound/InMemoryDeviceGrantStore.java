package com.iaf.console.bff.clients.adapters.outbound;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;

import reactor.core.publisher.Mono;

/** Device grants of one instance, in memory, by user code and by device code hash; the ones past their keeping are swept on every new grant. */
@Component
@Profile("!mongo")
public class InMemoryDeviceGrantStore implements DeviceGrantStore {

    private final Map<String, DeviceGrant> byUserCode = new HashMap<>();
    private final Map<String, String> userCodeByDeviceCode = new HashMap<>();

    @Override
    public Mono<Boolean> create(DeviceGrant grant) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                sweep(grant.requestedAt());
                if (byUserCode.containsKey(grant.userCode()) || userCodeByDeviceCode.containsKey(grant.deviceCodeHash())) {
                    return false;
                }
                byUserCode.put(grant.userCode(), grant);
                userCodeByDeviceCode.put(grant.deviceCodeHash(), grant.userCode());
                return true;
            }
        });
    }

    @Override
    public Mono<DeviceGrant> findByUserCode(String userCode, Instant now) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                DeviceGrant grant = byUserCode.get(userCode);
                return grant != null && grant.kept(now) ? grant : null;
            }
        });
    }

    @Override
    public Mono<DeviceGrant> findByDeviceCode(String deviceCodeHash, Instant now) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                String userCode = userCodeByDeviceCode.get(deviceCodeHash);
                DeviceGrant grant = userCode == null ? null : byUserCode.get(userCode);
                return grant != null && grant.kept(now) ? grant : null;
            }
        });
    }

    @Override
    public Mono<Long> countRequestedFrom(String ip, Instant since) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                return byUserCode.values().stream().filter(g -> g.requestedFrom().ip().equals(ip) && !g.requestedAt().isBefore(since)).count();
            }
        });
    }

    @Override
    public Mono<DeviceGrant> update(DeviceGrant grant, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            synchronized (this) {
                DeviceGrant current = byUserCode.get(grant.userCode());
                if (current == null || current.version() != expectedVersion || !current.deviceCodeHash().equals(grant.deviceCodeHash())) {
                    return null;
                }
                byUserCode.put(grant.userCode(), grant);
                return grant;
            }
        });
    }

    private void sweep(Instant now) {
        byUserCode.values().removeIf(grant -> {
            if (grant.kept(now)) {
                return false;
            }
            userCodeByDeviceCode.remove(grant.deviceCodeHash());
            return true;
        });
    }
}
