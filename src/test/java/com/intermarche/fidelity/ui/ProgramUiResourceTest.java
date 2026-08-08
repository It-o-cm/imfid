package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.batch.BatchResult;
import com.intermarche.fidelity.batch.BatchService;
import com.intermarche.fidelity.batch.BatchType;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link ProgramUiResource}: the Programme screen render, the setting
 * mutation and the two-step batch trigger (§23.4, §25.1, §32.3). Every collaborator is mocked:
 * the {@link AdminService} and the {@link BatchService} with Mockito, and the two Panache static
 * readers — {@link FidelityProgramSetting#getString(String, String)} and
 * {@link BatchRunLog#lastRun(String)} — with {@code mockStatic} in try-with-resources, so no test
 * ever reaches a real database.
 * <p>
 * The {@code static native} Qute template of the nested {@code Templates} class has no
 * instrumentable body, so under a plain unit run the {@code program} render evaluates every guard
 * and argument fully (that computation carries the branches) and then reaches the native boundary,
 * which raises an {@link UnsatisfiedLinkError}; the render tests drive both {@code canWrite} arms
 * and assert that boundary. The {@code setting} and {@code batch} POST mutations return a real
 * {@link Response} that is asserted directly. Each guard is covered on both arms and, for the
 * compound {@code catch (IllegalArgumentException | NullPointerException)} of {@code batch}, on
 * each leg (§29, §29.6): the {@code IllegalArgumentException} of an unknown type and the
 * {@code NullPointerException} of a null type, plus the {@code dryRun ? :} ternary on both its
 * simulate and execute arms.
 * <p>
 * Justified residue (§29): the loop-exit branch of {@code for (BatchType type : BatchType.values())}
 * and the whole {@code return Templates.program(...)} line stay uncovered. That batch loop is the
 * last statement before the {@code static native} render, which raises {@link UnsatisfiedLinkError}
 * and never returns, so JaCoCo's probe covering the loop-exit branch and the render line never
 * fires. Recording them would require the render to return normally — i.e. a real Qute runtime
 * under {@code @QuarkusTest}/H2, forbidden here — so the class rests at 7/8 branches (87 %).
 */
class ProgramUiResourceTest {

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private ProgramUiResource resource;

    /**
     * The mocked mutation service.
     */
    private AdminService admin;

    /**
     * The mocked batch engine.
     */
    private BatchService batches;

    /**
     * Wires a fresh resource with its mocked collaborators.
     */
    @BeforeEach
    void setUp() {
        resource = new ProgramUiResource();
        admin = mock(AdminService.class);
        batches = mock(BatchService.class);
        resource.admin = admin;
        resource.batches = batches;
    }

    /**
     * Builds a security context resolving the write role to the given decision.
     *
     * @param canWrite Whether the user holds the {@code fid-admin} role.
     * @return The mocked security context.
     */
    private SecurityContext context(boolean canWrite) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(canWrite);
        return securityContext;
    }

    /**
     * Returns the URL-decoded query of a redirect response, so assertions read the plain notice
     * regardless of the percent/plus encoding chosen by the URI builder.
     *
     * @param response The redirect response.
     * @return The decoded query string.
     */
    private String decodedQuery(Response response) {
        return java.net.URLDecoder.decode(response.getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // --------------------------------------------------
    // program
    // --------------------------------------------------

    /**
     * {@code program}: a non-null security context takes the {@code canWrite} true arm; the setting
     * loop reads each value through {@code getString} and the batch loop resolves a last run for one
     * type and null for the others, then the render reaches the native boundary.
     */
    @Test
    @DisplayName("program: authenticated writer renders and reaches the native boundary")
    void programWithContext() {
        BatchRunLog run = new BatchRunLog();
        try (MockedStatic<FidelityProgramSetting> settings = mockStatic(FidelityProgramSetting.class);
                MockedStatic<BatchRunLog> logs = mockStatic(BatchRunLog.class)) {
            settings.when(() -> FidelityProgramSetting.getString(anyString(), anyString()))
                    .thenReturn("value");
            logs.when(() -> BatchRunLog.lastRun(BatchType.EXPIRY.name())).thenReturn(run);
            logs.when(() -> BatchRunLog.lastRun(BatchType.PURGE.name())).thenReturn(null);
            logs.when(() -> BatchRunLog.lastRun(BatchType.ACTIVATION_VOID.name())).thenReturn(null);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.program("saved", true, context(true)));
        }
    }

    /**
     * {@code program}: a null security context takes the {@code canWrite} false arm (the guard
     * short-circuits without probing the role); the loops still run and the render reaches the
     * native boundary.
     */
    @Test
    @DisplayName("program: null security context takes the canWrite false arm")
    void programNullContext() {
        try (MockedStatic<FidelityProgramSetting> settings = mockStatic(FidelityProgramSetting.class);
                MockedStatic<BatchRunLog> logs = mockStatic(BatchRunLog.class)) {
            settings.when(() -> FidelityProgramSetting.getString(anyString(), anyString()))
                    .thenReturn("value");
            logs.when(() -> BatchRunLog.lastRun(anyString())).thenReturn(null);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.program(null, false, null));
        }
    }

    // --------------------------------------------------
    // setting
    // --------------------------------------------------

    /**
     * {@code setting}: a successful save takes the try arm and redirects with a success notice.
     */
    @Test
    @DisplayName("setting: success redirects with a success notice")
    void settingSuccess() {
        Response response = resource.setting(FidelityProgramSetting.KEY_PROGRAM_ZONE, "Europe/Paris");
        verify(admin).setProgramSetting(FidelityProgramSetting.KEY_PROGRAM_ZONE, "Europe/Paris");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Setting program.zone saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code setting}: an {@link AdminException} takes the catch arm and redirects with the refusal
     * message as a failure notice.
     */
    @Test
    @DisplayName("setting: refusal redirects with a failure notice")
    void settingFailure() {
        when(admin.setProgramSetting("bad.key", "x")).thenThrow(new AdminException("Unknown setting"));
        Response response = resource.setting("bad.key", "x");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Unknown setting"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // batch
    // --------------------------------------------------

    /**
     * {@code batch}: a dry-run trims and upper-cases the type, takes the simulate arm of both the
     * {@code verb} and the "Simulation" ternaries and redirects with the simulate notice.
     */
    @Test
    @DisplayName("batch: dry-run takes the simulate arms and redirects")
    void batchDryRun() {
        BatchResult result = new BatchResult(BatchType.EXPIRY.name(), true);
        result.accountsAffected = 3;
        result.totalAmount = new BigDecimal("12.00");
        when(batches.run(BatchType.EXPIRY, true)).thenReturn(result);
        Response response = resource.batch(" expiry ", true);
        verify(batches).run(BatchType.EXPIRY, true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains(
                "notice=EXPIRY — Simulation : expirerait/purgerait 12.00 € sur 3 compte(s)"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code batch}: an execution takes the execute arm of both the {@code verb} and the
     * "Exécution" ternaries and redirects with the execute notice.
     */
    @Test
    @DisplayName("batch: execution takes the execute arms and redirects")
    void batchExecute() {
        BatchResult result = new BatchResult(BatchType.PURGE.name(), false);
        result.accountsAffected = 5;
        result.totalAmount = new BigDecimal("43.50");
        when(batches.run(BatchType.PURGE, false)).thenReturn(result);
        Response response = resource.batch("PURGE", false);
        verify(batches).run(BatchType.PURGE, false);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains(
                "notice=PURGE — Exécution : a traité 43.50 € sur 5 compte(s)"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code batch}: an unknown type name takes the {@code IllegalArgumentException} leg of the
     * compound catch and redirects with a failure notice, without running any batch.
     */
    @Test
    @DisplayName("batch: unknown type takes the IllegalArgumentException leg")
    void batchUnknownType() {
        Response response = resource.batch("NOPE", true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Unknown batch 'NOPE'"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    /**
     * {@code batch}: a null type makes {@code type.trim()} throw, taking the
     * {@code NullPointerException} leg of the compound catch, and redirects with a failure notice.
     */
    @Test
    @DisplayName("batch: null type takes the NullPointerException leg")
    void batchNullType() {
        Response response = resource.batch(null, true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/program", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Unknown batch 'null'"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }
}
