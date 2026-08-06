/**
 * The EARN engine — phase 2 evaluated on the valued basket (§15, §22).
 *
 * <p>Hosts {@code POST /earn}, whose input is the {@code
 * { valuationRequest, valuationResponse }} pair the POS forwards untouched.
 * Derives net baskets and consumption predicates, joins the card context
 * (account, monthly visits, cap accruals, community memberships, activations)
 * and the rules in force, then returns the earn block.</p>
 *
 * <p>{@code POST /earn} is a pure read: zero side effect (§30.2). The basis is
 * net (I1); non-cumul is by consumption predicate then priority + exclusive
 * flag (I2); one HALF_UP rounding per rule (I4); caps truncate in order
 * rule&rarr;community&rarr;global, each truncation traced (I5).</p>
 */
package com.intermarche.fidelity.earn;
