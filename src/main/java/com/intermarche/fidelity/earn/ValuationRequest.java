package com.intermarche.fidelity.earn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /valuation} request the POS transmits verbatim to {@code /earn} (§15,
 * §22). imfid reads from it the card ({@link #customerCode}), the store and the
 * basket date ({@link #createdAt}) — the projection evaluates rules and card context
 * at the order date (§31.1). The card is authoritative from here at ingestion (§27.4).
 * <p>
 * Ignores unknown properties so a forward-compatible request never breaks the read
 * (§26.4). Fields are public to keep the DTO a plain Jackson carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValuationRequest {

    /**
     * The card number (= loyalty {@code customerCode}); absent or unknown yields an
     * empty earn (§15, §20).
     */
    public String customerCode;

    /**
     * The store code the basket was priced at.
     */
    public String storeCode;

    /**
     * The basket date; the projection reference of the rule windows and dated card
     * context (§31.1). ISO-8601, interpreted at the program zone (§25.1).
     */
    public LocalDateTime createdAt;

    /**
     * The requested basket lines; kept for completeness — imfid derives amounts from
     * the response, not from here (§22.1). Never null (§31.2).
     */
    public List<RequestItem> items = new ArrayList<>();

    /**
     * One requested basket line of the {@code /valuation} input (§22).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequestItem {

        /**
         * The basket line id.
         */
        public String lineId;

        /**
         * The line EAN.
         */
        public String produceEan;

        /**
         * The line quantity.
         */
        public Double quantity;
    }
}
