package com.intermarche.fidelity.earn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The body of {@code POST /api/earn}: the {@code /valuation} couple transmitted as
 * is by the POS (§22, §27.1). imfid derives assiettes and the burnable base from
 * this couple and joins the card context (§15).
 * <p>
 * Fields are public to keep the DTO a plain Jackson carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EarnRequest {

    /**
     * The {@code /valuation} request (card, store, date) (§22).
     */
    public ValuationRequest valuationRequest;

    /**
     * The {@code /valuation} response (offers, advantages, totals) (§22).
     */
    public ValuationResponse valuationResponse;
}
