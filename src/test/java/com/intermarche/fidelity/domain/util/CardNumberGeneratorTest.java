package com.intermarche.fidelity.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link CardNumberGenerator}: the loyalty card number factory
 * (§33.1) that emits a 13-digit EAN-13 opened by the reserved {@code card.prefix} setting,
 * a zero-padded sequence and a check digit. Both arms and every leg of each guard and
 * ternary are exercised (§29, §29.6): the digits-only prefix fallback (§31.2), the
 * over-long base truncation, the sequence walk on collision and the exhaustion throw. The
 * Panache active-record finders ({@code find}, {@code count}) and the setting reader are
 * mocked through {@link PanacheEntityBase} in try-with-resources per the imfid unit bench.
 * The class holds no temporal logic, so nothing here reads a {@code DateTimeProvider}.
 */
class CardNumberGeneratorTest {

    /**
     * The instance under test — a stateless generator, rebuilt per test for isolation.
     */
    private final CardNumberGenerator generator = new CardNumberGenerator();

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult()} yields the given value.
     *
     * @param first The value returned by {@code firstResult()}.
     * @return A mocked query.
     */
    @SuppressWarnings("unchecked")
    private static PanacheQuery<FidelityProgramSetting> settingQuery(FidelityProgramSetting first) {
        PanacheQuery<FidelityProgramSetting> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(first);
        return query;
    }

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult()} yields the given account.
     *
     * @param first The account returned by {@code firstResult()}.
     * @return A mocked query.
     */
    @SuppressWarnings("unchecked")
    private static PanacheQuery<FidelityAccount> accountQuery(FidelityAccount first) {
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(first);
        return query;
    }

    /**
     * Stubs the {@code card.prefix} setting lookup to return the given setting (or none).
     *
     * @param panache The Panache static mock.
     * @param setting The setting the key resolves to, or {@code null} when absent.
     */
    private static void stubPrefixSetting(MockedStatic<PanacheEntityBase> panache,
            FidelityProgramSetting setting) {
        PanacheQuery<FidelityProgramSetting> query = settingQuery(setting);
        panache.when(() -> PanacheEntityBase.find("key", FidelityProgramSetting.KEY_CARD_PREFIX))
                .thenReturn(query);
    }

    /**
     * Stubs the card-number lookup for an exact candidate to resolve to the given account.
     *
     * @param panache   The Panache static mock.
     * @param candidate The card number the lookup is keyed on.
     * @param account   The account the candidate resolves to, or {@code null} when free.
     */
    private static void stubCardLookup(MockedStatic<PanacheEntityBase> panache,
            String candidate, FidelityAccount account) {
        PanacheQuery<FidelityAccount> query = accountQuery(account);
        panache.when(() -> PanacheEntityBase.find("cardNumber", candidate)).thenReturn(query);
    }

    /**
     * Builds a {@link FidelityProgramSetting} carrying the given raw value.
     *
     * @param value The stored value.
     * @return The setting.
     */
    private static FidelityProgramSetting settingWithValue(String value) {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = value;
        return setting;
    }

    /**
     * When no {@code card.prefix} setting exists, {@code getString} returns the default and the
     * digits-only guard keeps it (non-null value, non-empty digits). Sequence 1 over an empty
     * account table yields the padded default-prefix EAN-13.
     */
    @Test
    @DisplayName("generate(): absent setting uses the 299 default prefix")
    void generateDefaultPrefixWhenSettingAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, null);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "2990000000019", null);
            assertEquals("2990000000019", generator.generate());
        }
    }

    /**
     * A present setting whose stored value is {@code null} makes {@code getString} return null,
     * exercising the {@code value == null} arm of {@code digitsOnly}, which falls back to the
     * default prefix.
     */
    @Test
    @DisplayName("generate(): null setting value falls back to the default prefix")
    void generateNullSettingValueFallsBackToDefault() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, settingWithValue(null));
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "2990000000019", null);
            assertEquals("2990000000019", generator.generate());
        }
    }

    /**
     * A configured prefix with no digits at all (e.g. {@code "ABC"}) leaves the strip empty,
     * exercising the {@code digits.isEmpty()} true arm, which falls back to the default prefix.
     */
    @Test
    @DisplayName("generate(): all-letters prefix falls back to the default prefix")
    void generateBlankNonDigitPrefixFallsBackToDefault() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, settingWithValue("ABC"));
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "2990000000019", null);
            assertEquals("2990000000019", generator.generate());
        }
    }

    /**
     * A mixed configured prefix ({@code "7A7"}) keeps only its digits ({@code "77"}), exercising
     * the {@code digits.isEmpty()} false arm, and the shorter prefix widens the zero padding.
     */
    @Test
    @DisplayName("generate(): non-digits are stripped from the configured prefix")
    void generateStripsNonDigitsFromConfiguredPrefix() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, settingWithValue("7A7"));
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "7700000000019", null);
            assertEquals("7700000000019", generator.generate());
        }
    }

    /**
     * A first candidate already taken drives the loop's {@code != null} false arm (walk forward),
     * then the next sequence resolves free — the {@code == null} true arm returns it.
     */
    @Test
    @DisplayName("generate(): walks forward past a collision")
    void generateWalksForwardOnCollision() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, null);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "2990000000019", new FidelityAccount());
            stubCardLookup(panache, "2990000000026", null);
            assertEquals("2990000000026", generator.generate());
        }
    }

    /**
     * A prefix longer than the 12-digit base drives {@code base12}'s {@code body.length() > 12}
     * true arm: the body is right-truncated to its last 12 digits before the check digit.
     */
    @Test
    @DisplayName("generate(): an over-long base is truncated to its last 12 digits")
    void generateTruncatesOverlongBaseToLast12() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, settingWithValue("2991234567890123"));
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            stubCardLookup(panache, "3456789012319", null);
            assertEquals("3456789012319", generator.generate());
        }
    }

    /**
     * A base whose weighted sum is a multiple of ten drives the check digit's outer modulo to
     * zero ({@code (10 - 0) % 10 == 0}). Sequence 4 (three existing accounts) is such a base.
     */
    @Test
    @DisplayName("generate(): a check digit of zero is emitted for a sum multiple of ten")
    void generateCheckDigitZeroWhenSumMultipleOfTen() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, null);
            panache.when(PanacheEntityBase::count).thenReturn(3L);
            stubCardLookup(panache, "2990000000040", null);
            assertEquals("2990000000040", generator.generate());
        }
    }

    /**
     * When every candidate collides, the sequence walk exhausts its bound and the generator
     * throws — the loop's exit arm and the {@code IllegalStateException}.
     */
    @Test
    @DisplayName("generate(): throws when no free number can be found")
    void generateThrowsWhenNoFreeNumber() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubPrefixSetting(panache, null);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            PanacheQuery<FidelityAccount> taken = accountQuery(new FidelityAccount());
            panache.when(() -> PanacheEntityBase.find(
                    ArgumentMatchers.eq("cardNumber"), (Object) ArgumentMatchers.any()))
                    .thenReturn(taken);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    generator::generate);
            assertEquals("Unable to generate a free card number", error.getMessage());
        }
    }
}
