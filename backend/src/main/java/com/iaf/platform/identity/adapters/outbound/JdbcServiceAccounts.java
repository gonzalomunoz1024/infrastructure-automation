package com.iaf.platform.identity.adapters.outbound;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

/**
 * The service accounts of the {@code postgres} profile: one row per account in
 * {@code service_accounts}, found by the SHA-256 of its token. The token itself is shown once
 * when the account is made and is never stored; neither is anything that could rebuild it.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcServiceAccounts implements ServiceAccountStore {

    private final Jdbc jdbc;

    public JdbcServiceAccounts(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ServiceAccount account) {
        String doc = jdbc.json(account);
        jdbc.upsert("UPDATE service_accounts SET token_hash = ?, created_at = ?, created_ns = ?, doc = ? WHERE id = ?",
                new Object[] {account.tokenHash(), Jdbc.time(account.createdAt()), Jdbc.nanos(account.createdAt()), doc, account.id()},
                "INSERT INTO service_accounts (id, token_hash, created_at, created_ns, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {account.id(), account.tokenHash(), Jdbc.time(account.createdAt()), Jdbc.nanos(account.createdAt()), doc});
    }

    @Override
    public Optional<ServiceAccount> byTokenHash(String hash) {
        return hash == null ? Optional.empty()
                : jdbc.first("SELECT doc FROM service_accounts WHERE token_hash = ?", (rs, i) -> jdbc.read(rs.getString(1), ServiceAccount.class), hash);
    }

    /** Oldest first. */
    @Override
    public List<ServiceAccount> all() {
        return jdbc.query("SELECT doc FROM service_accounts ORDER BY created_ns NULLS LAST, id", (rs, i) -> jdbc.read(rs.getString(1), ServiceAccount.class));
    }
}
