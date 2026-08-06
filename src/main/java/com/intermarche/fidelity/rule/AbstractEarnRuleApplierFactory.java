package com.intermarche.fidelity.rule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Base class for the earn rule factories, loading each mechanic's JSON Schema from
 * a classpath resource by convention ({@code /schemas/rule/<TYPE>.json}) and
 * caching it (§12, §21.3).
 * <p>
 * Keeping the schema in a versioned resource — rather than inlined — lets the same
 * file validate the specification and generate the admin form, and makes the
 * catalogue reviewable at a glance. Subclasses only declare their rule type and
 * build their applier.
 */
public abstract class AbstractEarnRuleApplierFactory implements EarnRuleApplierFactory {

    /**
     * The lazily loaded, cached schema string; loaded once on first access.
     */
    private volatile String cachedSchema;

    /**
     * Returns the classpath path of this factory's schema resource; by convention
     * {@code /schemas/rule/<TYPE>.json}. Overridable for a non-conventional layout.
     *
     * @return The schema resource path.
     */
    protected String schemaResourcePath() {
        return "/schemas/rule/" + getRuleType() + ".json";
    }

    /**
     * Loads (once) and returns the JSON Schema of this factory's specification from
     * its classpath resource.
     *
     * @return The JSON Schema as a string, never null.
     * @throws IllegalStateException when the resource is missing or unreadable — a
     *                               deployment error surfaced at startup, not a
     *                               silent case (§12).
     */
    @Override
    public final String getSchema() {
        String local = cachedSchema;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedSchema == null) {
                cachedSchema = loadSchema();
            }
            return cachedSchema;
        }
    }

    /**
     * Reads the schema resource from the classpath, failing loudly when absent.
     *
     * @return The schema content as a UTF-8 string.
     * @throws IllegalStateException when the resource is missing or unreadable.
     */
    private String loadSchema() {
        String path = schemaResourcePath();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing JSON Schema resource for rule type "
                        + getRuleType() + " at " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read JSON Schema resource for rule type "
                    + getRuleType() + " at " + path + ": " + e.getMessage(), e);
        }
    }
}
