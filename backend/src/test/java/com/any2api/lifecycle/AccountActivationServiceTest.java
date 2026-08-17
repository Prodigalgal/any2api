package com.any2api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.any2api.account.AccountEntity;
import com.any2api.account.AccountRepository;
import com.any2api.account.AccountStatus;
import com.any2api.account.LeasedProviderAccount;
import com.any2api.protocol.CanonicalEvent;
import com.any2api.protocol.CanonicalRequest;
import com.any2api.provider.InferenceProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.ProviderExecutionContext;
import com.any2api.provider.ProviderFailure;
import com.any2api.provider.ProviderManifest;
import com.any2api.provider.ProviderRegistry;
import com.any2api.provider.SupportLevel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AccountActivationServiceTest {

    @Test
    void pendingAccountSchedulesReauthenticationWithinTheRequestedWindow() {
        var fixture = fixture(AccountStatus.PENDING, false, true, true);
        var spread = Duration.ofHours(24);

        var result = fixture.service().activate(fixture.account().getId(), spread);

        assertThat(result.action()).isEqualTo(AccountActivationService.Action.REAUTHENTICATE);
        assertThat(result.spreadSeconds()).isEqualTo(86_400);
        verify(fixture.schedules()).scheduleReauthentication(
            fixture.account().getId(), "alpha", spread);
        verify(fixture.schedules(), never()).scheduleInitialProbe(
            fixture.account().getId(), "alpha", spread);
    }

    @Test
    void pendingAccountWithoutReauthenticationSchedulesARealProbe() {
        var fixture = fixture(AccountStatus.PENDING, false, false, true);
        var spread = Duration.ofMinutes(30);

        var result = fixture.service().activate(fixture.account().getId(), spread);

        assertThat(result.action()).isEqualTo(AccountActivationService.Action.PROBE);
        verify(fixture.schedules()).scheduleInitialProbe(
            fixture.account().getId(), "alpha", spread);
    }

    @Test
    void activeEnabledAccountIsRevalidatedBeforeRemainingInThePool() {
        var fixture = fixture(AccountStatus.ACTIVE, true, true, true);

        var result = fixture.service().activate(
            fixture.account().getId(), AccountActivationService.DEFAULT_SPREAD);

        assertThat(result.action()).isEqualTo(AccountActivationService.Action.PROBE);
        verify(fixture.schedules()).scheduleInitialProbe(
            fixture.account().getId(), "alpha", AccountActivationService.DEFAULT_SPREAD);
    }

    @Test
    void bannedAccountsAndUnboundedSpreadsAreRejected() {
        var banned = fixture(AccountStatus.BANNED, false, true, true);
        assertThatThrownBy(() -> banned.service().activate(
                banned.account().getId(), Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("banned");

        var pending = fixture(AccountStatus.PENDING, false, true, true);
        assertThatThrownBy(() -> pending.service().activate(
                pending.account().getId(), Duration.ofDays(8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("7 days");
        verify(pending.schedules(), never()).scheduleReauthentication(
            pending.account().getId(), "alpha", Duration.ofDays(8));
    }

    private Fixture fixture(
        AccountStatus status,
        boolean enabled,
        boolean reauthentication,
        boolean keepalive
    ) {
        var account = AccountEntity.create("alpha", "upstream", null, null, Map.of());
        account.updateState(status, enabled);
        var repository = mock(AccountRepository.class);
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
        var schedules = mock(LifecycleScheduleService.class);
        var providers = ProviderRegistry.allEnabled(List.of(provider(
            reauthentication, keepalive)));
        return new Fixture(account, schedules,
            new AccountActivationService(repository, providers, schedules));
    }

    private InferenceProvider provider(boolean reauthentication, boolean keepalive) {
        var capabilities = new java.util.EnumMap<ProviderCapability, SupportLevel>(
            ProviderCapability.class);
        capabilities.put(ProviderCapability.CHAT_COMPLETIONS, SupportLevel.NATIVE);
        capabilities.put(ProviderCapability.RESPONSES, SupportLevel.NATIVE);
        if (reauthentication) {
            capabilities.put(ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE);
        }
        if (keepalive) {
            capabilities.put(ProviderCapability.ACCOUNT_KEEPALIVE, SupportLevel.NATIVE);
        }
        return new InferenceProvider() {
            @Override
            public ProviderManifest manifest() {
                return new ProviderManifest(
                    "alpha", "alpha", "1", "1", List.of(), Map.copyOf(capabilities), true);
            }

            @Override
            public Flux<CanonicalEvent> generate(
                CanonicalRequest request,
                ProviderExecutionContext context,
                LeasedProviderAccount account
            ) {
                return Flux.empty();
            }

            @Override
            public ProviderFailure classify(Throwable error) {
                return new ProviderFailure("test", "test", false, Map.of());
            }
        };
    }

    private record Fixture(
        AccountEntity account,
        LifecycleScheduleService schedules,
        AccountActivationService service
    ) {}
}
