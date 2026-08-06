package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.batch.BatchResult;
import com.intermarche.fidelity.batch.BatchService;
import com.intermarche.fidelity.batch.BatchType;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The Programme screen (§23.4): the administrable program settings (§25.1) and the batch
 * supervision — each of the three batches (1st-March expiry, 24-month purge, two-month
 * activation void) triggerable in two steps, Simulate (dry-run) then Execute, with the
 * last run shown. Every action follows the POST → 303 → notice cycle (§21.3).
 */
@Path("/ui/program")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class ProgramUiResource {

    /**
     * The program settings shown, with their labels and fallbacks.
     */
    private static final String[][] SETTINGS = {
            {FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, "Plafond global mensuel (€)", "400.00"},
            {FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, "TTL du bail de réservation (s)", "900"},
            {FidelityProgramSetting.KEY_PROGRAM_ZONE, "Fuseau du programme", "Europe/Paris"},
            {FidelityProgramSetting.KEY_CARD_PREFIX, "Préfixe de carte réservé", "299"}
    };

    /**
     * The mutation service.
     */
    @Inject
    AdminService admin;

    /**
     * The batch engine.
     */
    @Inject
    BatchService batches;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The program template.
         *
         * @param view The program view model.
         * @return The rendered program screen.
         */
        static native TemplateInstance program(ProgramView view);
    }

    /**
     * Renders the program settings and batch supervision (§23.4).
     *
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered program screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance program(@QueryParam("notice") String notice,
                                    @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        List<ProgramView.Setting> settings = new ArrayList<>();
        for (String[] s : SETTINGS) {
            settings.add(new ProgramView.Setting(s[0], s[1], FidelityProgramSetting.getString(s[0], s[2])));
        }
        List<ProgramView.Batch> batchViews = new ArrayList<>();
        for (BatchType type : BatchType.values()) {
            batchViews.add(new ProgramView.Batch(type.name(), BatchRunLog.lastRun(type.name())));
        }
        return Templates.program(new ProgramView(settings, batchViews, UiSupport.canWrite(sc), notice, noticeOk));
    }

    /**
     * Saves a program setting (§25.1).
     *
     * @param key   The setting key.
     * @param value The setting value.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/setting")
    @Transactional
    public Response setting(@FormParam("key") String key, @FormParam("value") String value) {
        try {
            admin.setProgramSetting(key, value);
            return UiSupport.redirect("/ui/program", "Setting " + key + " saved", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/program", e.getMessage(), false);
        }
    }

    /**
     * Triggers a batch, simulate or execute (§23.4, §32.3).
     *
     * @param type   The batch type.
     * @param dryRun Whether to simulate.
     * @return A redirect with a notice summarizing the run.
     */
    @POST
    @Path("/batch")
    @Transactional
    public Response batch(@FormParam("type") String type, @FormParam("dryRun") @DefaultValue("true") boolean dryRun) {
        BatchType batchType;
        try {
            batchType = BatchType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UiSupport.redirect("/ui/program", "Unknown batch '" + type + "'", false);
        }
        BatchResult result = batches.run(batchType, dryRun);
        String verb = dryRun ? "expirerait/purgerait" : "a traité";
        String notice = batchType.name() + " — " + (dryRun ? "Simulation : " : "Exécution : ")
                + verb + " " + result.totalAmount + " € sur " + result.accountsAffected + " compte(s)";
        return UiSupport.redirect("/ui/program", notice, true);
    }
}
