package com.intermarche.fidelity.account;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.domain.CardHolder;
import com.intermarche.fidelity.domain.FidelityAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * The local holder directory — the fallback identity service (§33.3).
 * <p>
 * When a CRM access is configured ({@code imfid.crm.url}), identity resolution
 * belongs to the CRM: this service refuses writes and lookups so imfid stays
 * pseudonymous. When no CRM is configured, it records the holder's identity
 * (last name, first name, phone, e-mail) and answers the POS card lookup by
 * phone, e-mail or name. All mutations go through here — the single mutation
 * service of the holder domain.
 */
@ApplicationScoped
public class HolderService {

    /**
     * The maximum number of matches a lookup returns — bounds enumeration and
     * keeps the POS answer displayable (§33.3).
     */
    static final int MAX_RESULTS = 20;

    /**
     * The configured CRM access URL; when present and non-blank the local
     * directory is disabled.
     */
    @ConfigProperty(name = "imfid.crm.url")
    Optional<String> crmUrl;

    /**
     * Indicates whether the local holder directory is active: true when no CRM
     * access is configured (§33.3).
     *
     * @return true when holder identities are stored and searchable locally.
     */
    public boolean localDirectoryEnabled() {
        return crmUrl.filter(url -> !url.isBlank()).isEmpty();
    }

    /**
     * Creates or updates the holder identity of a card (§33.3).
     *
     * @param card      The card number.
     * @param lastName  The holder's last name; mandatory.
     * @param firstName The holder's first name; optional.
     * @param phone     The holder's phone; optional.
     * @param email     The holder's e-mail; optional.
     * @return The persisted holder.
     * @throws AdminException when the directory is CRM-managed, the card is
     *                        unknown, or the last name is blank.
     */
    @Transactional
    public CardHolder upsertHolder(String card, String lastName, String firstName,
                                   String phone, String email) {
        requireLocalDirectory();
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        if (account == null) {
            throw new AdminException("No card with number '" + card + "'");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new AdminException("Holder last name is mandatory (§33.3)");
        }
        CardHolder holder = CardHolder.findByAccount(account);
        if (holder == null) {
            holder = new CardHolder();
            holder.account = account;
        }
        holder.lastName = lastName.trim();
        holder.lastNameSearch = CardHolder.searchName(lastName);
        holder.firstName = firstName == null || firstName.isBlank() ? null : firstName.trim();
        holder.firstNameSearch = CardHolder.searchName(firstName);
        holder.phone = phone == null || phone.isBlank() ? null : phone.trim();
        holder.phoneSearch = CardHolder.searchPhone(phone);
        holder.email = CardHolder.normalizeEmail(email);
        holder.persist();
        return holder;
    }

    /**
     * Returns the holder recorded for a card, or null when none (or when the
     * directory is CRM-managed, in which case nothing is stored locally).
     *
     * @param account The account.
     * @return The holder, or null.
     */
    public CardHolder holderOf(FidelityAccount account) {
        return CardHolder.findByAccount(account);
    }

    /**
     * Looks up cards by holder identity — the POS fallback search (§33.3). One
     * criterion at least is required; phone wins over e-mail, e-mail over name.
     * Phone and e-mail match exactly after normalization; the name matches by
     * prefix on the last name, optionally narrowed by a first-name prefix, both
     * insensitive to case and diacritics.
     *
     * @param phone     The phone to match, in any keyed-in format; optional.
     * @param email     The e-mail to match; optional.
     * @param name      The last-name prefix to match; optional.
     * @param firstName An optional first-name prefix narrowing the name match.
     * @return The matching holders, at most {@link #MAX_RESULTS}, never null.
     * @throws AdminException when the directory is CRM-managed or no criterion
     *                        is given.
     */
    public List<CardHolder> lookup(String phone, String email, String name, String firstName) {
        requireLocalDirectory();
        String phoneSearch = CardHolder.searchPhone(phone);
        if (phoneSearch != null) {
            return CardHolder.listByPhone(phoneSearch, MAX_RESULTS);
        }
        String emailSearch = CardHolder.normalizeEmail(email);
        if (emailSearch != null) {
            return CardHolder.listByEmail(emailSearch, MAX_RESULTS);
        }
        String nameSearch = CardHolder.searchName(name);
        if (nameSearch != null) {
            return CardHolder.listByName(nameSearch, CardHolder.searchName(firstName), MAX_RESULTS);
        }
        throw new AdminException("A phone, an e-mail or a name is required (§33.3)");
    }

    /**
     * Fails when the local directory is disabled because a CRM access is
     * configured (§33.3).
     *
     * @throws AdminException when identity is CRM-managed.
     */
    private void requireLocalDirectory() {
        if (!localDirectoryEnabled()) {
            throw new AdminException("Holder identity is managed by the CRM (§33.3)");
        }
    }
}
