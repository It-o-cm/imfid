package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityRule;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * The view model of the schema-driven rule form (§21.3, §23.3), serving its three modes:
 * creation (blank or prefilled from a duplicated source), in-place edition of a rule not
 * yet entered into force (§18), and read-only consultation — the rule sheet is this very
 * same generated form with every control frozen, so consultation and edition render the
 * specification identically (same labels, widgets and percent conversions).
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class RuleFormView {

    /**
     * The HTML datetime-local format of the window bounds.
     */
    private static final DateTimeFormatter HTML_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * The page title ("Nouvelle règle" or "Éditer la règle X").
     */
    public String title;

    /**
     * The form POST action ({@code /ui/rules/create} or {@code /ui/rules/X/update}).
     */
    public String action;

    /**
     * The submit button label.
     */
    public String submitLabel;

    /**
     * Whether the form edits an existing rule (code frozen) rather than creating one.
     */
    public boolean editMode;

    /**
     * Whether the form is a read-only consultation sheet: every control is frozen and
     * no submit is rendered (§23.3).
     */
    public boolean readOnly;

    /**
     * The rule state badge of the consultation sheet (ACTIVE, UPCOMING, CLOSED,
     * INACTIVE); null outside consultation mode.
     */
    public String state;

    /**
     * The CSS badge class of the consultation state; null outside consultation mode.
     */
    public String badgeClass;

    /**
     * Whether the consulted rule window is still open, i.e. whether the "Fermer"
     * gesture applies (§18); meaningful in consultation mode only.
     */
    public boolean open;

    /**
     * Whether the consulted rule may be edited in place: only a rule not yet entered
     * into force (UPCOMING) has no history and no replay depending on it (§18);
     * meaningful in consultation mode only.
     */
    public boolean editable;

    /**
     * The rule code (prefilled for a duplicate, frozen in edit mode).
     */
    public String code = "";

    /**
     * The selected rule type, or empty to keep the first registered type.
     */
    public String type = "";

    /**
     * The printed label.
     */
    public String label = "";

    /**
     * The window start, in HTML datetime-local format.
     */
    public String validFrom = "";

    /**
     * The window end, in HTML datetime-local format, or empty while open.
     */
    public String validTo = "";

    /**
     * The evaluation priority.
     */
    public int priority;

    /**
     * The per-card monthly cap in euro, or empty when none.
     */
    public String monthlyCapPerCard = "";

    /**
     * Whether the rule consumes its lines (§15, I2).
     */
    public boolean exclusive;

    /**
     * Whether the rule is active.
     */
    public boolean active = true;

    /**
     * The initial JSON specification the form editor loads, or empty.
     */
    public String specification = "";

    /**
     * The aggregated {@code type -> schema} JSON published to the form generator (§12).
     */
    public String schemasJson;

    /**
     * The community codes JSON array for the community widget.
     */
    public String communitiesJson;

    /**
     * The registered rule types of the select.
     */
    public Set<String> types;

    /**
     * Whether the signed-in user may write (§21.4).
     */
    public boolean canWrite;

    /**
     * The one-shot notice, or null.
     */
    public String notice;

    /**
     * Whether the notice reports a success.
     */
    public boolean noticeOk;

    /**
     * Builds the creation-mode view (blank; the caller may prefill code, type and
     * specification for a duplicate, §23.3).
     *
     * @param schemasJson     The aggregated schemas JSON.
     * @param communitiesJson The community codes JSON.
     * @param types           The registered rule types.
     * @param canWrite        Whether the user may write.
     * @return The creation-mode view.
     */
    public static RuleFormView creation(String schemasJson, String communitiesJson,
                                        Set<String> types, boolean canWrite) {
        RuleFormView view = new RuleFormView();
        view.title = "Nouvelle règle";
        view.action = "/ui/rules/create";
        view.submitLabel = "Créer la règle";
        view.editMode = false;
        view.schemasJson = schemasJson;
        view.communitiesJson = communitiesJson;
        view.types = types;
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Builds the edition-mode view from a rule not yet entered into force (§18): every
     * backbone field and the specification are prefilled, the code is frozen.
     *
     * @param rule            The rule to edit.
     * @param schemasJson     The aggregated schemas JSON.
     * @param communitiesJson The community codes JSON.
     * @param types           The registered rule types.
     * @param canWrite        Whether the user may write.
     * @return The edition-mode view.
     */
    public static RuleFormView edition(FidelityRule rule, String schemasJson, String communitiesJson,
                                       Set<String> types, boolean canWrite) {
        RuleFormView view = new RuleFormView();
        view.title = "Éditer la règle " + rule.code;
        view.action = "/ui/rules/" + rule.code + "/update";
        view.submitLabel = "Enregistrer";
        view.editMode = true;
        view.code = rule.code;
        view.type = rule.type;
        view.label = rule.label;
        view.validFrom = format(rule.validFrom);
        view.validTo = format(rule.validTo);
        view.priority = rule.priority;
        view.monthlyCapPerCard = rule.monthlyCapPerCard != null ? rule.monthlyCapPerCard.toPlainString() : "";
        view.exclusive = rule.exclusive;
        view.active = rule.active;
        view.specification = rule.specification;
        view.schemasJson = schemasJson;
        view.communitiesJson = communitiesJson;
        view.types = types;
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Builds the read-only consultation view of a rule (§23.3): the same prefilled form
     * as edition, frozen, with the state badge and the applicable gestures (edit for an
     * upcoming rule, duplicate, close while open).
     *
     * @param rule            The rule to consult.
     * @param now             The current program instant, resolving the state badge.
     * @param schemasJson     The aggregated schemas JSON.
     * @param communitiesJson The community codes JSON.
     * @param types           The registered rule types.
     * @param canWrite        Whether the user may write.
     * @return The consultation-mode view.
     */
    public static RuleFormView consultation(FidelityRule rule, LocalDateTime now,
                                            String schemasJson, String communitiesJson,
                                            Set<String> types, boolean canWrite) {
        RuleFormView view = edition(rule, schemasJson, communitiesJson, types, canWrite);
        view.title = "Règle " + rule.code;
        view.action = "";
        view.submitLabel = "";
        view.editMode = false;
        view.readOnly = true;
        RuleRow row = RuleRow.of(rule, now);
        view.state = row.state;
        view.badgeClass = row.getBadgeClass();
        view.open = "ACTIVE".equals(row.state) || "UPCOMING".equals(row.state);
        view.editable = "UPCOMING".equals(row.state);
        return view;
    }

    /**
     * Formats a window bound for an HTML datetime-local input.
     *
     * @param value The bound, may be null.
     * @return The formatted value, or empty when null.
     */
    private static String format(LocalDateTime value) {
        return value != null ? HTML_DATE_TIME.format(value) : "";
    }

    /**
     * Returns whether a notice must be displayed.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return notice != null && !notice.isBlank();
    }

    /**
     * Returns whether an initial specification must be published to the form editor.
     *
     * @return true when a specification is present.
     */
    public boolean isHasSpecification() {
        return specification != null && !specification.isBlank();
    }
}
