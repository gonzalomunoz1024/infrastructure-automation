package com.iaf.console.kit.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountScopeTest {

    @Test
    void holdsAccountRegionAndPrincipal() {
        AccountScope scope = AccountScope.of("123456789012", "global", "gonzalo");
        assertThat(scope.accountId()).isEqualTo("123456789012");
        assertThat(scope.region()).isEqualTo("global");
        assertThat(scope.principal()).isEqualTo("gonzalo");
        assertThat(scope.covers("123456789012", "global")).isTrue();
        assertThat(scope.covers("210987654321", "global")).isFalse();
        assertThat(scope.covers("123456789012", "eu-west")).isFalse();
    }

    @Test
    void rejectsWhatIsNotAnAccount() {
        assertThatThrownBy(() -> AccountScope.of("12345", "global", "gonzalo")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 digits");
        assertThatThrownBy(() -> AccountScope.of("12345678901a", "global", "gonzalo")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountScope.of(null, "global", "gonzalo")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AccountScope.of("123456789012", " ", "gonzalo")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region");
        assertThatThrownBy(() -> AccountScope.of("123456789012", "global", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void knowsAccountIds() {
        assertThat(AccountScope.isAccountId("210987654321")).isTrue();
        assertThat(AccountScope.isAccountId("2109876543210")).isFalse();
        assertThat(AccountScope.isAccountId("")).isFalse();
        assertThat(AccountScope.isAccountId(null)).isFalse();
    }
}
