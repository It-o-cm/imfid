package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CardHolder} — the local fallback directory entity (§33.3).
 * Only its pure normalization statics ({@link CardHolder#searchName(String)},
 * {@link CardHolder#searchPhone(String)}, {@link CardHolder#normalizeEmail(String)}) and its
 * change-detection {@link CardHolder#getChecksum()} carry logic; the Panache finders delegate
 * to the enhanced base and are exercised through {@link com.intermarche.fidelity.account.HolderService}.
 * No boot, no H2, no clock: every branch of each normalizer is driven directly — the null and
 * blank guards, the {@code +33}/{@code 0033} folding legs, the short-{@code 33} non-folding leg,
 * the no-digit residue, and each business field's contribution to the checksum.
 */
class CardHolderTest {

    // --------------------------------------------------
    // searchName
    // --------------------------------------------------

    /**
     * A null name yields null — the {@code name == null} true leg of the guard.
     */
    @Test
    @DisplayName("searchName: null yields null")
    void searchNameNull() {
        assertNull(CardHolder.searchName(null));
    }

    /**
     * A blank name yields null — the {@code name.isBlank()} true leg of the guard.
     */
    @Test
    @DisplayName("searchName: blank yields null")
    void searchNameBlank() {
        assertNull(CardHolder.searchName("   "));
    }

    /**
     * A name carrying diacritics is trimmed, stripped and uppercased (both guard legs false):
     * {@code Lefèvre} normalizes to {@code LEFEVRE}.
     */
    @Test
    @DisplayName("searchName: diacritics stripped, trimmed and uppercased")
    void searchNameStripsDiacritics() {
        assertEquals("LEFEVRE", CardHolder.searchName("  Lefèvre  "));
    }

    /**
     * A lowercase accented name uppercases and strips its accents: {@code garcía} normalizes to
     * {@code GARCIA}, so an accent-insensitive lookup matches.
     */
    @Test
    @DisplayName("searchName: lowercase accented name uppercases without accents")
    void searchNameUppercasesLowercaseAccented() {
        assertEquals("GARCIA", CardHolder.searchName("garcía"));
    }

    // --------------------------------------------------
    // searchPhone
    // --------------------------------------------------

    /**
     * A null phone yields null — the {@code phone == null} true leg of the guard.
     */
    @Test
    @DisplayName("searchPhone: null yields null")
    void searchPhoneNull() {
        assertNull(CardHolder.searchPhone(null));
    }

    /**
     * A blank phone yields null — the {@code phone.isBlank()} true leg of the guard.
     */
    @Test
    @DisplayName("searchPhone: blank yields null")
    void searchPhoneBlank() {
        assertNull(CardHolder.searchPhone("  "));
    }

    /**
     * A national number keyed with spaces, dots and dashes keeps only its digits, and starting
     * with {@code 0} it is not folded ({@code startsWith("0033")} false, {@code startsWith("33")}
     * false): {@code 06 12.34-56 78} normalizes to {@code 0612345678}.
     */
    @Test
    @DisplayName("searchPhone: separators dropped, national number kept as-is")
    void searchPhoneDropsSeparators() {
        assertEquals("0612345678", CardHolder.searchPhone("06 12.34-56 78"));
    }

    /**
     * A {@code +33} international number folds to the leading {@code 0} — the
     * {@code startsWith("33") && length > 9} true legs: {@code +33 6 12 34 56 78} normalizes to
     * {@code 0612345678}.
     */
    @Test
    @DisplayName("searchPhone: +33 folds to a leading 0")
    void searchPhoneFoldsPlus33() {
        assertEquals("0612345678", CardHolder.searchPhone("+33 6 12 34 56 78"));
    }

    /**
     * A {@code 0033} international number folds to the leading {@code 0} — the
     * {@code startsWith("0033")} true leg (checked before the {@code 33} leg): {@code 0033 6 11
     * 22 33 44} normalizes to {@code 0611223344}.
     */
    @Test
    @DisplayName("searchPhone: 0033 folds to a leading 0")
    void searchPhoneFolds0033() {
        assertEquals("0611223344", CardHolder.searchPhone("0033 6 11 22 33 44"));
    }

    /**
     * A short number starting with {@code 33} but of nine digits or fewer is not folded — the
     * {@code startsWith("33")} true leg paired with the {@code length > 9} false leg: {@code 33 44
     * 55} stays {@code 334455}, proving the compound guard is not a country-code strip on length
     * alone.
     */
    @Test
    @DisplayName("searchPhone: short 33-prefixed number is not folded")
    void searchPhoneShort33NotFolded() {
        assertEquals("334455", CardHolder.searchPhone("33 44 55"));
    }

    /**
     * A non-empty phone carrying no digit normalizes to null — the {@code digits.isEmpty()} true
     * leg of the residue ternary, distinct from the blank guard.
     */
    @Test
    @DisplayName("searchPhone: a digitless phone yields null")
    void searchPhoneDigitlessYieldsNull() {
        assertNull(CardHolder.searchPhone("(.)-"));
    }

    // --------------------------------------------------
    // normalizeEmail
    // --------------------------------------------------

    /**
     * A null e-mail yields null — the {@code email == null} true leg of the guard.
     */
    @Test
    @DisplayName("normalizeEmail: null yields null")
    void normalizeEmailNull() {
        assertNull(CardHolder.normalizeEmail(null));
    }

    /**
     * A blank e-mail yields null — the {@code email.isBlank()} true leg of the guard.
     */
    @Test
    @DisplayName("normalizeEmail: blank yields null")
    void normalizeEmailBlank() {
        assertNull(CardHolder.normalizeEmail("  "));
    }

    /**
     * A mixed-case padded e-mail is trimmed and lowercased (both guard legs false):
     * {@code  Marie.DURAND@Example.FR } normalizes to {@code marie.durand@example.fr}.
     */
    @Test
    @DisplayName("normalizeEmail: trimmed and lowercased")
    void normalizeEmailTrimsAndLowercases() {
        assertEquals("marie.durand@example.fr",
                CardHolder.normalizeEmail("  Marie.DURAND@Example.FR "));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    /**
     * Builds a holder carrying the four business fields the checksum hashes.
     *
     * @param lastName  The last name.
     * @param firstName The first name.
     * @param phone     The phone.
     * @param email     The e-mail.
     * @return The holder.
     */
    private CardHolder holder(String lastName, String firstName, String phone, String email) {
        CardHolder holder = new CardHolder();
        holder.lastName = lastName;
        holder.firstName = firstName;
        holder.phone = phone;
        holder.email = email;
        return holder;
    }

    /**
     * Two holders carrying the same business fields share a checksum — the change-detection
     * baseline against which each single-field mutation is measured.
     */
    @Test
    @DisplayName("getChecksum: equal business fields yield equal checksums")
    void checksumStableOnEqualFields() {
        CardHolder a = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        CardHolder b = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        assertEquals(a.getChecksum(), b.getChecksum());
    }

    /**
     * The checksum reacts to the last name — mutating it alone changes the checksum.
     */
    @Test
    @DisplayName("getChecksum: sensitive to the last name")
    void checksumSensitiveToLastName() {
        CardHolder base = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        CardHolder other = holder("Martin", "Marie", "0612345678", "marie@example.fr");
        assertNotEquals(base.getChecksum(), other.getChecksum());
    }

    /**
     * The checksum reacts to the first name — mutating it alone changes the checksum.
     */
    @Test
    @DisplayName("getChecksum: sensitive to the first name")
    void checksumSensitiveToFirstName() {
        CardHolder base = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        CardHolder other = holder("Durand", "Jacques", "0612345678", "marie@example.fr");
        assertNotEquals(base.getChecksum(), other.getChecksum());
    }

    /**
     * The checksum reacts to the phone — mutating it alone changes the checksum.
     */
    @Test
    @DisplayName("getChecksum: sensitive to the phone")
    void checksumSensitiveToPhone() {
        CardHolder base = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        CardHolder other = holder("Durand", "Marie", "0699999999", "marie@example.fr");
        assertNotEquals(base.getChecksum(), other.getChecksum());
    }

    /**
     * The checksum reacts to the e-mail — mutating it alone changes the checksum.
     */
    @Test
    @DisplayName("getChecksum: sensitive to the e-mail")
    void checksumSensitiveToEmail() {
        CardHolder base = holder("Durand", "Marie", "0612345678", "marie@example.fr");
        CardHolder other = holder("Durand", "Marie", "0612345678", "other@example.fr");
        assertNotEquals(base.getChecksum(), other.getChecksum());
    }
}
