package com.intermarche.fidelity.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.earn.EarnEngine;
import com.intermarche.fidelity.earn.EarnResult;
import com.intermarche.fidelity.earn.ValuationReader;
import com.intermarche.fidelity.earn.ValuationReading;
import com.intermarche.fidelity.earn.ValuationReconciliationException;
import com.intermarche.fidelity.earn.ValuationResponse;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDateTime;

/**
 * The Simulator (§23.5) — the connector acceptance test of §22 turned into a screen and
 * the product demonstration support. Paste a {@code /valuation} couple, choose a card and
 * force an evaluation date (§26.3), and see the earn rule by rule, the {@code capsApplied},
 * the {@code burnableBase} and the warnings. It is strictly a read: it never pollutes the
 * account it uses (§30.2), and the forced date is passed to the engine, never applied to
 * the global clock.
 */
@Path("/ui/simulator")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class SimulatorUiResource {

    /**
     * The shared JSON mapper for the pasted couple.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /**
     * The reader turning the pasted response into valued lines (§22).
     */
    @Inject
    ValuationReader reader;

    /**
     * The earn engine (§15).
     */
    @Inject
    EarnEngine engine;

    /**
     * The program clock for the default evaluation date (§25.1).
     */
    @Inject
    ProgramClock clock;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The simulator template.
         *
         * @param view The simulator view model.
         * @return The rendered simulator.
         */
        static native TemplateInstance simulator(SimulatorView view);
    }

    /**
     * Renders the empty simulator, prefilling the reference card (§23.5).
     *
     * @return The rendered simulator.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance simulator() {
        return Templates.simulator(SimulatorView.empty("LOYALTY-DEMO-001",
                clock.today().atTime(10, 0).toString()));
    }

    /**
     * Simulates the earn of a pasted couple for a card at a forced date — a read, no side
     * effect (§30.2, §23.5).
     *
     * @param response The pasted {@code /valuation} response JSON.
     * @param card     The card number.
     * @param date     The forced evaluation date-time (ISO local).
     * @return The rendered simulator with the result.
     */
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance simulate(@FormParam("response") String response, @FormParam("card") String card,
                                     @FormParam("date") String date) {
        LocalDateTime evalDateTime = parseDateTime(date);
        try {
            ValuationResponse valuation = MAPPER.readValue(
                    response == null ? "{}" : response, ValuationResponse.class);
            ValuationReading reading = reader.read(valuation);
            FidelityAccount account = card == null || card.isBlank()
                    ? null : FidelityAccount.findByCardNumber(card.trim());
            EarnResult result = engine.evaluate(reading, account, evalDateTime, true);
            return Templates.simulator(SimulatorView.of(response, card, date, result,
                    account == null ? "Carte inconnue — earn vide (§20)" : null));
        } catch (ValuationReconciliationException e) {
            return Templates.simulator(SimulatorView.error(response, card, date,
                    "Réconciliation §22.1 échouée : " + e.getMessage()));
        } catch (Exception e) {
            return Templates.simulator(SimulatorView.error(response, card, date,
                    "Couple invalide : " + e.getMessage()));
        }
    }

    /**
     * Parses a forced evaluation date-time, falling back to the current program time
     * (§26.3, §31.2).
     *
     * @param value The date-time string.
     * @return The evaluation instant.
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return clock.now();
        }
        try {
            String v = value.trim();
            return v.length() == 16 ? LocalDateTime.parse(v + ":00")
                    : (v.length() == 10 ? LocalDateTime.parse(v + "T00:00:00") : LocalDateTime.parse(v));
        } catch (Exception e) {
            return clock.now();
        }
    }
}
