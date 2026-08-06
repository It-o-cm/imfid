/**
 * Domain utilities — chiefly the {@code DateTimeProvider} (imvaluation pattern).
 *
 * <p>Single injected source of truth for every clock-driven decision: civil-day
 * visits (I3), rule validity windows, reservation leases (I11), the 1st-March
 * expiry batch and the 24-month purge. No class reads the system clock directly
 * (§24.6); the reference time zone is the program's, never the server's (§30.3).</p>
 */
package com.intermarche.fidelity.domain.util;
