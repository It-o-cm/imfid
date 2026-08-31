package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of per-card activations (§14, §18).
 * <p>
 * Activations carry the e-coupon activations, the engaged challenges and the
 * completed "gold level" missions over a period; a rule requiring activation
 * ({@code ECOUPON_EARN}, {@code CHALLENGE_EARN}) only earns when a matching
 * activation is active at the fiscal date (§31.1). A row is matched on
 * {@code (account, ruleCode, periodStart)}.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): CARD_NUMBER (key), RULE_CODE, PERIOD_START, PERIOD_END,
 * MISSION_DONE, dates in ISO
 * local date; a blank {@code PERIOD_END} leaves the period open. An unknown card,
 * or a missing {@code RULE_CODE}/{@code PERIOD_START}, fails the row and triggers
 * the staged fallback. The {@code fid-admin} role guard (§24.1) is attached in the
 * security build step.
 */
@Path("/fidelity/activations/import")
@ApplicationScoped
@RunOnVirtualThread
public class FidelityActivationCsvResource extends ImporterCsvResource {

    /** Header name of the natural key: the activated card number. */
    static final String COL_CARD_NUMBER = "CARD_NUMBER";
    /** Header name of the activated rule code. */
    static final String COL_RULE_CODE = "RULE_CODE";
    /** Header name of the activation period start. */
    static final String COL_PERIOD_START = "PERIOD_START";
    /** Header name of the activation period end. */
    static final String COL_PERIOD_END = "PERIOD_END";
    /** Header name of the completed-mission flag. */
    static final String COL_MISSION_DONE = "MISSION_DONE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_RULE_CODE, COL_PERIOD_START, COL_PERIOD_END, COL_MISSION_DONE);

    /**
     * Context key holding the pre-fetched accounts map (card number &rarr; account).
     */
    private static final String CTX_ACCOUNTS = "__CTX_ACCOUNTS__";

    /**
     * Imports or updates per-card activations from a CSV stream (§18).
     *
     * @param inputStream The activation CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importActivations(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_CARD_NUMBER, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches every referenced account (by card number) into the context.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCards The distinct card numbers present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return The context map holding the accounts map.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCards, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (parsedLines.isEmpty()) {
            return contextMap;
        }
        Map<String, FidelityAccount> accounts = new HashMap<>();
        if (!targetCards.isEmpty()) {
            for (FidelityAccount account : FidelityAccount.<FidelityAccount>list("cardNumber in ?1", targetCards)) {
                accounts.put(account.cardNumber, account);
            }
        }
        contextMap.put(CTX_ACCOUNTS, accounts);
        return contextMap;
    }

    /**
     * Creates an activation or updates the period end and mission flag of the one
     * matching {@code (account, ruleCode, periodStart)}.
     *
     * @param data      The row to process.
     * @param entityMap The context map holding the accounts map.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        FidelityAccount account = resolveAccount(entityMap, data.code);
        if (account == null) {
            throw new IllegalArgumentException("Card '" + data.code + "' not found.");
        }
        String ruleCode = safeGetNonBlank(data, COL_RULE_CODE);
        if (ruleCode == null) {
            throw new IllegalArgumentException("ruleCode is mandatory.");
        }
        LocalDate periodStart = safeParseLocalDate(data, COL_PERIOD_START);
        if (periodStart == null) {
            throw new IllegalArgumentException("periodStart is mandatory.");
        }
        LocalDate periodEnd = safeParseLocalDate(data, COL_PERIOD_END);
        boolean missionDone = safeParseBoolean(data, COL_MISSION_DONE);
        FidelityActivation activation = FidelityActivation.find(
                "account = ?1 and ruleCode = ?2 and periodStart = ?3", account, ruleCode, periodStart).firstResult();
        if (activation == null) {
            activation = new FidelityActivation();
            activation.account = account;
            activation.ruleCode = ruleCode;
            activation.periodStart = periodStart;
            activation.periodEnd = periodEnd;
            activation.missionDone = missionDone;
            counters[0]++;
            Panache.getEntityManager().persist(activation);
        } else if (!Objects.equals(activation.periodEnd, periodEnd) || activation.missionDone != missionDone) {
            activation.periodEnd = periodEnd;
            activation.missionDone = missionDone;
            counters[1]++;
        }
    }

    /**
     * The activation is resolved from the account and the row's key fields in
     * {@link #processLineLogic}, so the 1-by-1 fallback carries no pre-fetched entity.
     *
     * @param data The row to look up.
     * @return Always null.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return null;
    }

    /**
     * Resolves the account of a row from the context map, or by a fresh lookup in
     * the 1-by-1 fallback where the context is absent.
     *
     * @param entityMap  The context map.
     * @param cardNumber The card number to resolve.
     * @return The {@link FidelityAccount}, or null when unknown.
     */
    private static FidelityAccount resolveAccount(Map<String, Object> entityMap, String cardNumber) {
        @SuppressWarnings("unchecked")
        Map<String, FidelityAccount> accounts = (Map<String, FidelityAccount>) entityMap.get(CTX_ACCOUNTS);
        if (accounts != null) {
            return accounts.get(cardNumber);
        }
        return FidelityAccount.findByCardNumber(cardNumber);
    }
}
