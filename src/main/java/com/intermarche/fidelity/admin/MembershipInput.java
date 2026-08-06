package com.intermarche.fidelity.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * One membership entry submitted by the community workbench (§23.2) — the direct-editing
 * pattern submits the whole membership set, and a member absent from the submission is
 * removed (deletion by omission, §21.3). A plain Jackson carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MembershipInput {

    /**
     * The member card number.
     */
    public String card;

    /**
     * The membership window start.
     */
    public LocalDate validFrom;

    /**
     * The membership window end, or null while open.
     */
    public LocalDate validTo;
}
