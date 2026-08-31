package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of community memberships (§14, §18).
 * <p>
 * A membership ties an account to a community over a validity window carrying the
 * annual October/February renewals; the recalc at ingestion evaluates it at the
 * ticket fiscal date (§31.1). A row is matched on
 * {@code (account, community, validFrom)} so a re-import only extends or closes the
 * window through {@code validTo}.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): CARD_NUMBER (key), COMMUNITY_CODE, VALID_FROM, VALID_TO,
 * dates in ISO local date; a
 * blank {@code VALID_TO} leaves the window open. An unknown card or community, or a
 * missing {@code validFrom}, fails the row and triggers the staged fallback. The
 * {@code fid-admin} role guard (§24.1) is attached in the security build step.
 */
@Path("/fidelity/memberships/import")
@ApplicationScoped
@RunOnVirtualThread
public class FidelityMembershipCsvResource extends ImporterCsvResource {

    /** Header name of the natural key: the member card number. */
    static final String COL_CARD_NUMBER = "CARD_NUMBER";
    /** Header name of the community code. */
    static final String COL_COMMUNITY_CODE = "COMMUNITY_CODE";
    /** Header name of the membership window start. */
    static final String COL_VALID_FROM = "VALID_FROM";
    /** Header name of the membership window end. */
    static final String COL_VALID_TO = "VALID_TO";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_COMMUNITY_CODE, COL_VALID_FROM, COL_VALID_TO);

    /**
     * Context key holding the pre-fetched accounts map (card number &rarr; account).
     */
    private static final String CTX_ACCOUNTS = "__CTX_ACCOUNTS__";

    /**
     * Context key holding the pre-fetched communities map (code &rarr; community).
     */
    private static final String CTX_COMMUNITIES = "__CTX_COMMUNITIES__";

    /**
     * Imports or updates community memberships from a CSV stream (§18).
     *
     * @param inputStream The membership CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importMemberships(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_CARD_NUMBER, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches every referenced account (by card number) and community (by code)
     * into the context, so each row resolves its endpoints without a query.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCards The distinct card numbers present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return The context map bundling accounts and communities.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCards, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (parsedLines.isEmpty()) {
            return contextMap;
        }
        contextMap.put(CTX_ACCOUNTS, fetchAccounts(targetCards));
        Set<String> communityCodes = new HashSet<>();
        for (LineData data : parsedLines) {
            String code = safeGetNonBlank(data, COL_COMMUNITY_CODE);
            if (code != null) {
                communityCodes.add(code);
            }
        }
        contextMap.put(CTX_COMMUNITIES, fetchCommunities(communityCodes));
        return contextMap;
    }

    /**
     * Bulk-fetches accounts by card number into a map.
     *
     * @param cardNumbers The card numbers to fetch.
     * @return A map of card number to {@link FidelityAccount}, never null.
     */
    private static Map<String, FidelityAccount> fetchAccounts(Set<String> cardNumbers) {
        Map<String, FidelityAccount> accounts = new HashMap<>();
        if (!cardNumbers.isEmpty()) {
            for (FidelityAccount account : FidelityAccount.<FidelityAccount>list("cardNumber in ?1", cardNumbers)) {
                accounts.put(account.cardNumber, account);
            }
        }
        return accounts;
    }

    /**
     * Bulk-fetches communities by code into a map.
     *
     * @param codes The community codes to fetch.
     * @return A map of code to {@link FidelityCommunity}, never null.
     */
    private static Map<String, FidelityCommunity> fetchCommunities(Set<String> codes) {
        Map<String, FidelityCommunity> communities = new HashMap<>();
        if (!codes.isEmpty()) {
            for (FidelityCommunity community : FidelityCommunity.<FidelityCommunity>list("code in ?1", codes)) {
                communities.put(community.code, community);
            }
        }
        return communities;
    }

    /**
     * Creates a membership or updates the {@code validTo} of the one matching
     * {@code (account, community, validFrom)}.
     *
     * @param data      The row to process.
     * @param entityMap The context map bundling accounts and communities.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        FidelityAccount account = resolveAccount(entityMap, data.code);
        if (account == null) {
            throw new IllegalArgumentException("Card '" + data.code + "' not found.");
        }
        String communityCode = safeGetNonBlank(data, COL_COMMUNITY_CODE);
        FidelityCommunity community = resolveCommunity(entityMap, communityCode);
        if (community == null) {
            throw new IllegalArgumentException("Community '" + communityCode + "' not found.");
        }
        LocalDate validFrom = safeParseLocalDate(data, COL_VALID_FROM);
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom is mandatory.");
        }
        LocalDate validTo = safeParseLocalDate(data, COL_VALID_TO);
        FidelityMembership membership = FidelityMembership.find(
                "account = ?1 and community = ?2 and validFrom = ?3", account, community, validFrom).firstResult();
        if (membership == null) {
            membership = new FidelityMembership();
            membership.account = account;
            membership.community = community;
            membership.validFrom = validFrom;
            membership.validTo = validTo;
            counters[0]++;
            Panache.getEntityManager().persist(membership);
        } else if (!Objects.equals(membership.validTo, validTo)) {
            membership.validTo = validTo;
            counters[1]++;
        }
    }

    /**
     * No single entity keys a membership row; endpoints are resolved from fresh
     * lookups in {@link #processLineLogic}, so the 1-by-1 fallback carries no
     * pre-fetched entity.
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

    /**
     * Resolves the community of a row from the context map, or by a fresh lookup in
     * the 1-by-1 fallback where the context is absent.
     *
     * @param entityMap     The context map.
     * @param communityCode The community code to resolve.
     * @return The {@link FidelityCommunity}, or null when unknown.
     */
    private static FidelityCommunity resolveCommunity(Map<String, Object> entityMap, String communityCode) {
        if (communityCode == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, FidelityCommunity> communities = (Map<String, FidelityCommunity>) entityMap.get(CTX_COMMUNITIES);
        if (communities != null) {
            return communities.get(communityCode);
        }
        return FidelityCommunity.findByCode(communityCode);
    }
}
