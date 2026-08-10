package com.intermarche.fidelity;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Application readiness probe exposed at {@code /q/health} as {@code imfid-ready}
 * (§ dégradé of the integration specification). The POS treats this probe as its
 * fidelity-degraded detector and the qualification harness uses it as the deployment
 * gate, so it must reflect the real ability to serve, not merely that the process is up.
 * <p>
 * It confirms that the startup bootstrap has completed by two light Panache reads: the
 * bootstrap administrator account exists (§24.1) and the {@link FidelityProgramSetting}
 * carrying the program zone is loaded (§30.3) — the linchpin the earn engine needs to
 * date {@code earnYear} at the program zone. The datasource itself is already covered by
 * the automatic connection check, so it is not repeated here; the mailer is deliberately
 * out of scope (it may be mocked in qualification), as are the batch schedulers.
 * <p>
 * The probe never throws: a read failure is reported as {@code DOWN} with the name of the
 * missing element, so the endpoint degrades cleanly instead of surfacing a 500.
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    /**
     * The readiness check name surfaced in the {@code /q/health} payload.
     */
    static final String NAME = "imfid-ready";

    private static final Logger LOGGER = Logger.getLogger(ReadinessCheck.class);

    /**
     * The bootstrap administrator username, resolved the same way as the bootstrap that
     * creates the account (§24.1), so the probe looks up exactly the seeded login.
     */
    @ConfigProperty(name = "imfid.bootstrap.admin.username", defaultValue = "admin")
    String adminUsername;

    /**
     * Evaluates readiness with two light Panache reads inside a read-only transaction so
     * a Hibernate session is available outside any request context. Any failure is caught
     * and reported as {@code DOWN}; the method never propagates an exception.
     *
     * @return {@code UP} once both bootstrap invariants hold, {@code DOWN} with the name
     *         of the missing element otherwise.
     */
    @Override
    @Transactional
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(NAME);
        try {
            boolean adminPresent = AppUser.findByUsername(adminUsername) != null;
            boolean programZoneLoaded =
                    FidelityProgramSetting.findByKey(FidelityProgramSetting.KEY_PROGRAM_ZONE) != null;
            builder.withData("bootstrapAdmin", adminPresent)
                    .withData("programSetting", programZoneLoaded);
            if (adminPresent && programZoneLoaded) {
                return builder.up().build();
            }
            String missing = !adminPresent ? "bootstrap admin account" : "program zone setting";
            return builder.withData("missing", missing).down().build();
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "%s probe failed reading bootstrap state", NAME);
            return builder.withData("error", String.valueOf(e.getMessage())).down().build();
        }
    }
}
