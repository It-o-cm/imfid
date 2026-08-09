package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.UserAdminService;
import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The operator-account administration screen (§24.1) — the list with its role and status,
 * and the creation/edition form. Ported from imvaluation's user management, adapted to
 * imfid's {@code pos}/{@code fid-admin} role matrix, its admin chrome and its POST → 303 →
 * notice convention (§21.3).
 * <p>
 * The whole resource is reserved to {@code fid-admin}: granting roles is the most
 * privileged gesture, so a lower privilege must never reach it. The write authority is the
 * {@code @RolesAllowed} annotation (§21.4); the mutations are delegated to
 * {@link UserAdminService}, never performed inline. The last-administrator and
 * no-self-deletion guards live in the service.
 */
@Path("/ui/users")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class UserUiResource {

    /**
     * The whitelist of sortable columns (guide §5.1).
     */
    private static final Set<String> SORTABLE = Set.of("username", "displayName");

    /**
     * The operator-account write service (§24.1).
     */
    @Inject
    UserAdminService users;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The account list template.
         *
         * @param view The list view model.
         * @return The rendered list.
         */
        static native TemplateInstance list(ListView<UserRow> view);

        /**
         * The account form template (creation and edition modes).
         *
         * @param view The form view model.
         * @return The rendered form.
         */
        static native TemplateInstance form(UserFormView view);
    }

    /**
     * Renders the filtered account list (§24.1).
     *
     * @param search   The login or display-name filter fragment.
     * @param role     The role filter.
     * @param sort     The sort key.
     * @param dir      The sort direction.
     * @param page     The one-based page number.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered list.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String search, @QueryParam("role") String role,
                                 @QueryParam("sort") @DefaultValue("username") String sort,
                                 @QueryParam("dir") @DefaultValue("asc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                                 @Context SecurityContext sc) {
        String sortKey = SORTABLE.contains(sort) ? sort : "username";
        boolean desc = "desc".equalsIgnoreCase(dir);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("1=1");
        if (search != null && !search.isBlank()) {
            where.append(" and (lower(username) like :q or lower(displayName) like :q or lower(email) like :q)");
            params.put("q", "%" + search.trim().toLowerCase() + "%");
        }
        if (role != null && !role.isBlank()) {
            where.append(" and roles like :role");
            params.put("role", "%" + role.trim() + "%");
        }
        long total = AppUser.count(where.toString(), params);
        int pageCount = (int) Math.max(1, Math.ceil((double) total / UiSupport.PAGE_SIZE));
        int current = UiSupport.clampPage(page, pageCount);
        List<AppUser> found = AppUser.find(
                        where + " order by " + sortKey + (desc ? " desc" : " asc") + ", username asc", params)
                .page(current - 1, UiSupport.PAGE_SIZE).list();
        String currentUser = currentUsername(sc);
        List<UserRow> rows = new ArrayList<>();
        for (AppUser user : found) {
            rows.add(UserRow.of(user, currentUser));
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (search != null) {
            filters.put("q", search);
        }
        if (role != null) {
            filters.put("role", role);
        }
        ListView<UserRow> view = new ListView<>(rows, "/ui/users", filters, sortKey, desc,
                current, pageCount, total, UiSupport.PAGE_SIZE, "user", notice, noticeOk, UiSupport.canWrite(sc));
        return Templates.list(view);
    }

    /**
     * Renders the blank creation form (§24.1).
     *
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form.
     */
    @GET
    @Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create(@QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                                   @Context SecurityContext sc) {
        UserFormView view = UserFormView.creation(UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Templates.form(view);
    }

    /**
     * Renders the edition form of an existing account (§24.1); an unknown identifier is
     * redirected back to the list.
     *
     * @param id       The account identifier.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form, or a redirect when the account is unknown.
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("id") Long id,
                         @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                         @Context SecurityContext sc) {
        AppUser user = AppUser.findById(id);
        if (user == null) {
            return UiSupport.redirect("/ui/users", "Account not found.", false);
        }
        UserFormView view = UserFormView.edition(user, UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Response.ok(Templates.form(view)).build();
    }

    /**
     * Creates an operator account from the form (§24.1).
     *
     * @param username    The login name.
     * @param password    The initial password.
     * @param displayName The display name, or blank.
     * @param email       The password-reset e-mail address, or blank.
     * @param roles       The granted roles.
     * @param active      Whether the account may sign in.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/create")
    public Response save(@FormParam("username") String username, @FormParam("password") String password,
                         @FormParam("displayName") String displayName, @FormParam("email") String email,
                         @FormParam("roles") List<String> roles, @FormParam("active") String active) {
        try {
            AppUser user = users.createUser(username, password, displayName, email,
                    toRoleSet(roles), active != null);
            return UiSupport.redirect("/ui/users", "Account '" + user.username + "' created.", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/users/new", e.getMessage(), false);
        }
    }

    /**
     * Updates an operator account from the form (§24.1); the login is frozen.
     *
     * @param id          The account identifier.
     * @param password    A new password, or blank to keep the current one.
     * @param displayName The display name, or blank.
     * @param email       The password-reset e-mail address, or blank.
     * @param roles       The granted roles.
     * @param active      Whether the account may sign in.
     * @param sc          The security context (the last-admin guard reads the caller).
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{id}/update")
    public Response update(@PathParam("id") Long id, @FormParam("password") String password,
                           @FormParam("displayName") String displayName, @FormParam("email") String email,
                           @FormParam("roles") List<String> roles, @FormParam("active") String active,
                           @Context SecurityContext sc) {
        try {
            AppUser user = users.updateUser(id, password, displayName, email,
                    toRoleSet(roles), active != null, currentUsername(sc));
            return UiSupport.redirect("/ui/users", "Account '" + user.username + "' updated.", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/users/" + id, e.getMessage(), false);
        }
    }

    /**
     * Deletes an operator account (§24.1); the service refuses the caller's own account
     * and the last active administrator.
     *
     * @param id The account identifier.
     * @param sc The security context (the self-deletion guard reads the caller).
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{id}/delete")
    public Response delete(@PathParam("id") Long id, @Context SecurityContext sc) {
        try {
            String login = users.deleteUser(id, currentUsername(sc));
            return UiSupport.redirect("/ui/users", "Account '" + login + "' deleted.", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/users", e.getMessage(), false);
        }
    }

    /**
     * Collects the submitted role values into a set, preserving submission order; the
     * service sanitizes it against the known roles.
     *
     * @param roles The roles posted by the form, may be null.
     * @return The submitted roles as a set, never null.
     */
    private Set<String> toRoleSet(List<String> roles) {
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }

    /**
     * Returns the signed-in operator's login, or an empty string when unauthenticated.
     *
     * @param sc The security context.
     * @return The caller's login name.
     */
    private String currentUsername(SecurityContext sc) {
        return sc != null && sc.getUserPrincipal() != null ? sc.getUserPrincipal().getName() : "";
    }
}
