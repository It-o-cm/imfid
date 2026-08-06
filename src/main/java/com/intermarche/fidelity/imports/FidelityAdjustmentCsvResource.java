package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk CSV import of {@code ADJUSTMENT} movements (§18, §32.1).
 * <p>
 * An {@code ADJUSTMENT} is a signed administration gesture with a mandatory reason,
 * outside every cap and expirable like earn ({@code earnYear} = civil year of the
 * gesture, §32.1). It carries the seeded initial balances (without it no burn is
 * demonstrable), after-sales corrections and acceptance fixtures. Each row is
 * keyed by its reference, stored as the movement {@code ticketRef} and used as the
 * natural idempotency key {@code (ticketRef, ADJUSTMENT, null)} (I8): a movement is
 * immutable once written, so a re-import of the same reference is a no-op.
 * <p>
 * Every account write happens under the per-card lock (SELECT FOR UPDATE, §30.1):
 * the movement is inserted and the denormalized balance is bumped atomically.
 * <p>
 * File format (5 pipe-delimited columns):
 * {@code reference|cardNumber|amount|movementDate|reason}, {@code amount} a signed
 * euro decimal and {@code movementDate} an ISO local date. An unknown card, a
 * missing amount/date or a blank reason fails the row and triggers the staged
 * fallback. The {@code fid-admin} role guard (§24.1) is attached in the security
 * build step.
 */
@Path("/fidelity/adjustments/import")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class FidelityAdjustmentCsvResource extends ImporterCsvResource {

    /**
     * Number of columns expected in the adjustment CSV.
     */
    private static final int COLUMNS = 5;

    /**
     * Imports {@code ADJUSTMENT} movements from a CSV stream (§18, §32.1).
     *
     * @param inputStream The adjustment CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importAdjustments(InputStream inputStream) {
        return this.importCsvStream(inputStream, COLUMNS);
    }

    /**
     * Bulk-fetches the {@code ADJUSTMENT} movements already imported for the chunk,
     * keyed by reference, so an already-present row is skipped (idempotency, I8).
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetRefs  The distinct references present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return A map of reference to the existing {@link FidelityMovement}, never null.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetRefs, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (!parsedLines.isEmpty() && !targetRefs.isEmpty()) {
            for (FidelityMovement movement : FidelityMovement.<FidelityMovement>list(
                    "type = ?1 and ticketRef in ?2", MovementType.ADJUSTMENT, targetRefs)) {
                contextMap.put(movement.ticketRef, movement);
            }
        }
        return contextMap;
    }

    /**
     * Inserts a new {@code ADJUSTMENT} movement under the per-card lock (§30.1) and
     * bumps the account balance; an already-present reference is left untouched.
     *
     * @param data      The row to process.
     * @param entityMap A map of reference to the existing {@link FidelityMovement}.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        if (entityMap.get(data.code) != null) {
            return; // Already imported: a movement is immutable (I8, §32.1).
        }
        String cardNumber = safeGetNonBlank(data.parts, 1);
        BigDecimal amount = safeParseBigDecimal(data.parts, 2);
        LocalDate movementDate = safeParseLocalDate(data.parts, 3);
        String reason = safeGetNonBlank(data.parts, 4);
        if (amount == null) {
            throw new IllegalArgumentException("amount is mandatory.");
        }
        if (movementDate == null) {
            throw new IllegalArgumentException("movementDate is mandatory.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason is mandatory for an ADJUSTMENT (§32.1).");
        }
        FidelityAccount account = lockAccount(cardNumber);
        if (account == null) {
            throw new IllegalArgumentException("Card '" + cardNumber + "' not found.");
        }
        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = MovementType.ADJUSTMENT;
        movement.amount = scaledAmount;
        movement.movementDate = movementDate;
        movement.earnYear = FidelityMovement.earnYearOf(movementDate);
        movement.ruleCode = null;
        movement.ticketRef = data.code;
        movement.reason = reason;
        Panache.getEntityManager().persist(movement);
        account.balance = account.balance.add(scaledAmount).setScale(2, RoundingMode.HALF_UP);
        counters[0]++;
    }

    /**
     * Looks up an already-imported adjustment by its natural key for the 1-by-1
     * fallback.
     *
     * @param data The row to look up.
     * @return The existing {@link FidelityMovement}, or null when not yet imported.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return FidelityMovement.findByNaturalKey(data.code, MovementType.ADJUSTMENT, null);
    }

    /**
     * Fetches an account by card number under a pessimistic write lock, serializing
     * every balance write per card (SELECT FOR UPDATE, §30.1).
     *
     * @param cardNumber The card number to lock; null yields no account.
     * @return The locked {@link FidelityAccount}, or null when unknown.
     */
    private FidelityAccount lockAccount(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        return FidelityAccount.<FidelityAccount>find("cardNumber", cardNumber)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    }
}
