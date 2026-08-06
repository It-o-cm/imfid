/**
 * Accounts, reservations/burn, fiscal-event ingestion and lifecycle batches.
 *
 * <p>POS-facing REST beyond earn (§27): {@code GET /accounts/{card}} and
 * {@code /accounts/{card}/movements}; lease-based burn — {@code POST
 * /burn/reservations}, {@code .../confirm} (idempotent), {@code DELETE
 * .../{id}} — reservation-then-confirmation, never a direct debit (I11).</p>
 *
 * <p>Credit happens at fiscal-event ingestion where the recompute is
 * authoritative (§26.1), idempotent by natural key ticketRef + type + ruleCode
 * (I8); every account write is under a per-card lock (§30.1). Balance is the sum
 * of movements; expiry consumes FIFO by {@code earnYear} (§30.3). Batches: the
 * 1st-March expiry, the 24-month purge and the activation void (§16).</p>
 */
package com.intermarche.fidelity.account;
