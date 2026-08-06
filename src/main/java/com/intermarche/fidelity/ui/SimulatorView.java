package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.earn.EarnResult;

/**
 * The view model of the Simulator (§23.5): the pasted inputs (to refill the form) and the
 * computed {@link EarnResult}, or an error message.
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class SimulatorView {

    /**
     * Whether a simulation has run.
     */
    public final boolean submitted;

    /**
     * The pasted response JSON (to refill).
     */
    public final String response;

    /**
     * The card number (to refill).
     */
    public final String card;

    /**
     * The forced date-time (to refill).
     */
    public final String date;

    /**
     * The computed result, or null on error / empty.
     */
    public final EarnResult result;

    /**
     * An error message, or null.
     */
    public final String error;

    /**
     * An informational note (e.g. unknown card), or null.
     */
    public final String note;

    /**
     * Builds a simulator view.
     *
     * @param submitted Whether a simulation ran.
     * @param response  The pasted response.
     * @param card      The card number.
     * @param date      The forced date.
     * @param result    The result, or null.
     * @param error     The error, or null.
     * @param note      The note, or null.
     */
    private SimulatorView(boolean submitted, String response, String card, String date,
                          EarnResult result, String error, String note) {
        this.submitted = submitted;
        this.response = response == null ? "" : response;
        this.card = card == null ? "" : card;
        this.date = date == null ? "" : date;
        this.result = result;
        this.error = error;
        this.note = note;
    }

    /**
     * Builds the empty (initial) view.
     *
     * @param card The prefilled card.
     * @param date The prefilled date.
     * @return The empty view.
     */
    public static SimulatorView empty(String card, String date) {
        return new SimulatorView(false, "", card, date, null, null, null);
    }

    /**
     * Builds a result view.
     *
     * @param response The pasted response.
     * @param card     The card number.
     * @param date     The forced date.
     * @param result   The result.
     * @param note     An informational note, or null.
     * @return The result view.
     */
    public static SimulatorView of(String response, String card, String date, EarnResult result, String note) {
        return new SimulatorView(true, response, card, date, result, null, note);
    }

    /**
     * Builds an error view.
     *
     * @param response The pasted response.
     * @param card     The card number.
     * @param date     The forced date.
     * @param error    The error message.
     * @return The error view.
     */
    public static SimulatorView error(String response, String card, String date, String error) {
        return new SimulatorView(true, response, card, date, null, error, null);
    }

    /**
     * Indicates whether an error is present.
     *
     * @return true when an error message is set.
     */
    public boolean isHasError() {
        return error != null;
    }

    /**
     * Indicates whether a note is present.
     *
     * @return true when a note is set.
     */
    public boolean isHasNote() {
        return note != null;
    }

    /**
     * Indicates whether a result is present.
     *
     * @return true when a result is set.
     */
    public boolean isHasResult() {
        return result != null;
    }
}
