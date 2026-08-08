package com.intermarche.fidelity.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link MembershipInput}: the Jackson carrier for one membership
 * entry submitted by the community workbench (§23.2), where a member absent from the
 * submission is removed by omission (§21.3). The class is a field-only data holder with an
 * implicit default constructor and no methods or branches; this suite asserts the three
 * fields round-trip verbatim, that the open-window {@code null} arm of {@code validTo} is
 * preserved, that a freshly constructed instance leaves every field {@code null}, and that
 * the {@code @JsonIgnoreProperties(ignoreUnknown = true)} contract holds by deserializing a
 * payload carrying an unknown property. Pure logic, no Panache and no clock: every date is a
 * fixed {@link LocalDate}, nothing here reads a {@code DateTimeProvider}.
 */
class MembershipInputTest {

    /**
     * Confirms a freshly constructed carrier leaves all three fields at their {@code null}
     * default, covering the untouched-field baseline.
     */
    @Test
    @DisplayName("default construction leaves every field null")
    void defaultConstructionLeavesFieldsNull() {
        MembershipInput input = new MembershipInput();
        assertNull(input.card);
        assertNull(input.validFrom);
        assertNull(input.validTo);
    }

    /**
     * Confirms all three fields store and return their assigned values verbatim for a closed
     * membership window (non-null {@code validTo} arm).
     */
    @Test
    @DisplayName("fields round-trip verbatim for a closed window")
    void fieldsRoundTripForClosedWindow() {
        MembershipInput input = new MembershipInput();
        input.card = "CARD-0001";
        input.validFrom = LocalDate.of(2026, 1, 1);
        input.validTo = LocalDate.of(2026, 12, 31);
        assertEquals("CARD-0001", input.card);
        assertEquals(LocalDate.of(2026, 1, 1), input.validFrom);
        assertEquals(LocalDate.of(2026, 12, 31), input.validTo);
    }

    /**
     * Covers the open-window arm: a {@code null} {@code validTo} is preserved while the card
     * and start date remain set.
     */
    @Test
    @DisplayName("null validTo marks an open membership window")
    void nullValidToMarksOpenWindow() {
        MembershipInput input = new MembershipInput();
        input.card = "CARD-0002";
        input.validFrom = LocalDate.of(2026, 6, 15);
        input.validTo = null;
        assertEquals("CARD-0002", input.card);
        assertEquals(LocalDate.of(2026, 6, 15), input.validFrom);
        assertNull(input.validTo);
    }

    /**
     * Asserts the {@code @JsonIgnoreProperties(ignoreUnknown = true)} contract: a payload
     * carrying an unrecognized property deserializes into the three known fields without
     * error.
     */
    @Test
    @DisplayName("unknown JSON properties are ignored on deserialization")
    void unknownPropertiesAreIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        String json = "{\"card\":\"CARD-0003\",\"validFrom\":\"2026-03-01\","
                + "\"validTo\":\"2026-09-30\",\"unexpected\":\"noise\"}";
        MembershipInput input = mapper.readValue(json, MembershipInput.class);
        assertEquals("CARD-0003", input.card);
        assertEquals(LocalDate.of(2026, 3, 1), input.validFrom);
        assertEquals(LocalDate.of(2026, 9, 30), input.validTo);
    }
}
