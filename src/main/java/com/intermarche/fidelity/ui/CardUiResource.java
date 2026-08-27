package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.account.AccountService;
import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.account.HolderService;
import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityReservation;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Cards administration screen (§23.1): the filtered list and the card sheet (barcode,
 * balance and month counters, active reservation, memberships, activations tab, movement
 * history) with its actions — create, adjust (ADJUSTMENT), transfer on loss/theft, and
 * resiliate. Every mutation follows the POST → 303 → notice cycle (§21.3); the write
 * controls are gated by {@code canWrite} doubled by the {@code fid-admin} role (§21.4,
 * §24.1).
 */
@Path("/ui/cards")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class CardUiResource {

    /**
     * The whitelist of sortable columns (guide §5.1) — an unknown key falls back to the
     * default.
     */
    private static final Set<String> SORTABLE = Set.of("cardNumber", "status", "balance", "lastUsedAt");

    /**
     * The account read service (§27.2).
     */
    @Inject
    AccountService accountService;

    /**
     * The mutation service (§28, §32.1).
     */
    @Inject
    AdminService admin;

    /**
     * The holder directory service (§33.3).
     */
    @Inject
    HolderService holders;

    /**
     * The program clock, for the month bounds of the visit count (§25.1).
     */
    @Inject
    com.intermarche.fidelity.domain.util.ProgramClock clock;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The card list template.
         *
         * @param view The list view model.
         * @return The rendered list.
         */
        static native TemplateInstance list(ListView<CardRow> view);

        /**
         * The card sheet template.
         *
         * @param view The card sheet view model.
         * @return The rendered sheet.
         */
        static native TemplateInstance detail(CardDetailView view);
    }

    /**
     * Renders the filtered, paginated card list (§23.1).
     *
     * @param number   The card-number filter fragment.
     * @param status   The status filter.
     * @param sort     The sort key.
     * @param dir      The sort direction.
     * @param page     The one-based page number.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered list.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("number") String number, @QueryParam("status") String status,
                                 @QueryParam("sort") @DefaultValue("cardNumber") String sort,
                                 @QueryParam("dir") @DefaultValue("asc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                                 @Context SecurityContext sc) {
        String sortKey = SORTABLE.contains(sort) ? sort : "cardNumber";
        boolean desc = "desc".equalsIgnoreCase(dir);
        AccountStatus statusFilter = parseStatus(status);

        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(number, statusFilter, params);
        long total = FidelityAccount.count(where, params);
        int pageCount = (int) Math.max(1, Math.ceil((double) total / UiSupport.PAGE_SIZE));
        int current = UiSupport.clampPage(page, pageCount);
        List<FidelityAccount> accounts = FidelityAccount.find(
                        where + " order by " + sortKey + (desc ? " desc" : " asc"), params)
                .page(current - 1, UiSupport.PAGE_SIZE).list();

        LocalDate monthStart = clock.monthStart(clock.today());
        LocalDate monthEnd = clock.monthEnd(clock.today());
        List<CardRow> rows = new java.util.ArrayList<>();
        for (FidelityAccount account : accounts) {
            int visits = (int) com.intermarche.fidelity.domain.EarnTrace.countVisits(
                    account.cardNumber, monthStart, monthEnd);
            rows.add(CardRow.of(account, visits));
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (number != null) {
            filters.put("number", number);
        }
        if (status != null) {
            filters.put("status", status);
        }
        ListView<CardRow> view = new ListView<>(rows, "/ui/cards", filters, sortKey, desc,
                current, pageCount, total, UiSupport.PAGE_SIZE, "card",
                notice, noticeOk, UiSupport.canWrite(sc));
        return Templates.list(view);
    }

    /**
     * Renders the card sheet (§23.1, §24.3).
     *
     * @param card     The card number.
     * @param mpage    The movement history page.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered sheet, or 404 when the card is unknown.
     */
    @GET
    @Path("/{card}")
    @Produces(MediaType.TEXT_HTML)
    public Response detail(@PathParam("card") String card, @QueryParam("mpage") @DefaultValue("1") int mpage,
                           @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                           @Context SecurityContext sc) {
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Unknown card").build();
        }
        AccountViews.Summary summary = accountService.summary(account);
        FidelityReservation reservation = FidelityReservation.findActiveForAccount(account);
        long movementCount = com.intermarche.fidelity.domain.FidelityMovement.count("account", account);
        int pageCount = (int) Math.max(1, Math.ceil((double) movementCount / UiSupport.PAGE_SIZE));
        int current = UiSupport.clampPage(mpage, pageCount);
        AccountViews.MovementPage movements = accountService.movements(account, current - 1, UiSupport.PAGE_SIZE);
        List<FidelityActivation> activations = FidelityActivation.listForAccount(account);
        List<FidelityCommunity> communities = FidelityCommunity.listAllByCode();

        CardDetailView view = new CardDetailView(summary, reservation, movements, current, pageCount,
                activations, communities, holders.holderOf(account), holders.localDirectoryEnabled(),
                UiSupport.canWrite(sc), notice, noticeOk);
        return Response.ok(Templates.detail(view)).build();
    }

    /**
     * Creates a card (§23.1, §33.1).
     *
     * @param status The initial status.
     * @return A redirect to the new card sheet with a notice.
     */
    @POST
    @Path("/create")
    @Transactional
    public Response create(@FormParam("status") @DefaultValue("ACTIVE") String status) {
        try {
            FidelityAccount account = admin.createCard(parseStatus(status));
            return UiSupport.redirect("/ui/cards/" + account.cardNumber,
                    "Card " + account.cardNumber + " created", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards", e.getMessage(), false);
        }
    }

    /**
     * Posts an ADJUSTMENT on a card (§32.1).
     *
     * @param card   The card number.
     * @param amount The signed amount.
     * @param reason The mandatory reason.
     * @return A redirect to the card sheet with a notice.
     */
    @POST
    @Path("/{card}/adjust")
    @Transactional
    public Response adjust(@PathParam("card") String card, @FormParam("amount") BigDecimal amount,
                           @FormParam("reason") String reason) {
        try {
            admin.adjustCard(card, amount, reason);
            return UiSupport.redirect("/ui/cards/" + card, "Adjustment posted", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Creates or updates the holder identity of a card (§33.3).
     *
     * @param card      The card number.
     * @param lastName  The holder's last name.
     * @param firstName The holder's first name.
     * @param phone     The holder's phone.
     * @param email     The holder's e-mail.
     * @return A redirect to the card sheet with a notice.
     */
    @POST
    @Path("/{card}/holder")
    @Transactional
    public Response holder(@PathParam("card") String card, @FormParam("lastName") String lastName,
                           @FormParam("firstName") String firstName, @FormParam("phone") String phone,
                           @FormParam("email") String email) {
        try {
            holders.upsertHolder(card, lastName, firstName, phone, email);
            return UiSupport.redirect("/ui/cards/" + card, "Holder identity saved", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Transfers a card on loss/theft (§28.1).
     *
     * @param card The card number.
     * @return A redirect to the new card sheet with a notice.
     */
    @POST
    @Path("/{card}/transfer")
    @Transactional
    public Response transfer(@PathParam("card") String card) {
        try {
            FidelityAccount target = admin.transferCard(card);
            return UiSupport.redirect("/ui/cards/" + target.cardNumber,
                    "Transferred from " + card + " to " + target.cardNumber, true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Resiliates a card (§28.1).
     *
     * @param card The card number.
     * @return A redirect to the card sheet with a notice.
     */
    @POST
    @Path("/{card}/resiliate")
    @Transactional
    public Response resiliate(@PathParam("card") String card) {
        try {
            admin.resiliateCard(card);
            return UiSupport.redirect("/ui/cards/" + card, "Card resiliated", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Upserts a community membership on a card (§28.4).
     *
     * @param card      The card number.
     * @param community The community code.
     * @param validFrom The window start.
     * @param validTo   The window end, or blank.
     * @return A redirect to the card sheet with a notice.
     */
    @POST
    @Path("/{card}/membership")
    @Transactional
    public Response membership(@PathParam("card") String card, @FormParam("community") String community,
                               @FormParam("validFrom") String validFrom, @FormParam("validTo") String validTo) {
        try {
            admin.upsertMembership(card, community, parseDate(validFrom), parseDate(validTo));
            return UiSupport.redirect("/ui/cards/" + card, "Membership saved", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Upserts a card activation (§24.3).
     *
     * @param card        The card number.
     * @param ruleCode    The rule enabled.
     * @param periodStart The period start.
     * @param periodEnd   The period end, or blank.
     * @param missionDone Whether the mission is done.
     * @return A redirect to the card sheet with a notice.
     */
    @POST
    @Path("/{card}/activation")
    @Transactional
    public Response activation(@PathParam("card") String card, @FormParam("ruleCode") String ruleCode,
                               @FormParam("periodStart") String periodStart, @FormParam("periodEnd") String periodEnd,
                               @FormParam("missionDone") boolean missionDone) {
        try {
            admin.setActivation(card, ruleCode, parseDate(periodStart), parseDate(periodEnd), missionDone);
            return UiSupport.redirect("/ui/cards/" + card, "Activation saved", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/cards/" + card, e.getMessage(), false);
        }
    }

    /**
     * Builds the filter WHERE clause and its parameters.
     *
     * @param number The card-number fragment.
     * @param status The status filter, or null.
     * @param params The parameter map to fill.
     * @return The WHERE clause (always valid).
     */
    private String buildWhere(String number, AccountStatus status, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("1=1");
        if (number != null && !number.isBlank()) {
            where.append(" and lower(cardNumber) like :number");
            params.put("number", "%" + number.trim().toLowerCase() + "%");
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        return where.toString();
    }

    /**
     * Parses a status filter, returning null when blank or unknown.
     *
     * @param status The status string.
     * @return The status, or null.
     */
    private AccountStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AccountStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses an ISO date, returning null when blank or malformed (§31.2).
     *
     * @param value The date string.
     * @return The date, or null.
     */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
