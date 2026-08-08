package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link EarnRequest}: the {@code /valuation} couple carrier
 * transmitted as is by the POS (§22, §27.1). The class is a branch-free Jackson DTO, so
 * the whole surface is the implicit default constructor and its two public fields; the
 * cases assert the fresh nulls and that each field carries the exact reference assigned.
 */
class EarnRequestTest {

    /**
     * A freshly constructed request exposes both couple halves as null (Jackson populates
     * them on bind), so the DTO ships no accidental defaults.
     */
    @Test
    @DisplayName("default construction leaves both couple halves null")
    void defaultConstructionLeavesBothHalvesNull() {
        EarnRequest request = new EarnRequest();
        assertNull(request.valuationRequest);
        assertNull(request.valuationResponse);
    }

    /**
     * The valuationRequest field is a plain carrier: it holds the exact reference assigned
     * and reads it back unchanged.
     */
    @Test
    @DisplayName("valuationRequest carries the exact reference assigned")
    void valuationRequestCarriesAssignedReference() {
        EarnRequest request = new EarnRequest();
        ValuationRequest valuationRequest = new ValuationRequest();
        request.valuationRequest = valuationRequest;
        assertSame(valuationRequest, request.valuationRequest);
        assertNull(request.valuationResponse);
    }

    /**
     * The valuationResponse field is a plain carrier: it holds the exact reference assigned
     * and reads it back unchanged.
     */
    @Test
    @DisplayName("valuationResponse carries the exact reference assigned")
    void valuationResponseCarriesAssignedReference() {
        EarnRequest request = new EarnRequest();
        ValuationResponse valuationResponse = new ValuationResponse();
        request.valuationResponse = valuationResponse;
        assertSame(valuationResponse, request.valuationResponse);
        assertNull(request.valuationRequest);
    }
}
