package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityRule;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link AbstractEarnRuleApplierFactory} — the base of the earn
 * rule factories (§12, §21.3): the conventional schema resource path, the lazily loaded
 * and cached schema string, its double-checked-locking guard, and the loud failure on a
 * missing or unreadable resource.
 * <p>
 * The class is abstract; it is exercised through a minimal same-package {@link TestFactory}
 * whose rule type drives the resource path. The present-resource legs read the real schemas
 * shipped on the classpath ({@code /schemas/rule/*.json}), so no test resource is added; the
 * missing-resource leg points at a type with no schema. The double-checked second read
 * (local {@code null} yet {@code cachedSchema} non-null) is a concurrent interleaving,
 * reproduced deterministically by holding the factory monitor, spinning until the caller is
 * {@link Thread.State#BLOCKED} on it, then injecting the field before releasing. The
 * {@code IOException} catch is reached with a {@link ThrowingClassLoader} whose
 * {@code getResourceAsStream} returns a stream that always throws.
 */
class AbstractEarnRuleApplierFactoryTest {

    /**
     * The binary name of the throwing factory, redefined under {@link ThrowingClassLoader}.
     */
    private static final String THROWING_FACTORY_NAME =
            "com.intermarche.fidelity.rule.AbstractEarnRuleApplierFactoryTest$ThrowingResourceFactory";

    /**
     * A minimal concrete factory: it declares a rule type (which drives the schema resource
     * path) and never builds an applier under these tests.
     */
    private static final class TestFactory extends AbstractEarnRuleApplierFactory {

        /**
         * The rule type this factory reports.
         */
        private final String ruleType;

        /**
         * Builds the factory over the given rule type.
         *
         * @param ruleType The rule type to report.
         */
        TestFactory(String ruleType) {
            this.ruleType = ruleType;
        }

        /**
         * Returns the configured rule type.
         *
         * @return The rule type, never null under these tests.
         */
        @Override
        public String getRuleType() {
            return ruleType;
        }

        /**
         * Never invoked here; the tested behaviour lives entirely in the base class.
         *
         * @param rule The rule to interpret.
         * @return {@code null}; unused.
         */
        @Override
        public EarnRuleApplier create(FidelityRule rule) {
            return null;
        }
    }

    /**
     * A factory whose class is redefined by {@link ThrowingClassLoader} so that its
     * {@code getClass().getResourceAsStream(...)} yields a stream that throws on read.
     */
    public static final class ThrowingResourceFactory extends AbstractEarnRuleApplierFactory {

        /**
         * Builds the throwing factory.
         */
        public ThrowingResourceFactory() {
            // No state: the throwing stream comes from the redefining class loader.
        }

        /**
         * Returns a rule type whose schema exists on the classpath, proving the failure is
         * the read error and not a missing resource.
         *
         * @return {@link FidelityRule#TYPE_COMMUNITY_EARN}.
         */
        @Override
        public String getRuleType() {
            return FidelityRule.TYPE_COMMUNITY_EARN;
        }

        /**
         * Never invoked here.
         *
         * @param rule The rule to interpret.
         * @return {@code null}; unused.
         */
        @Override
        public EarnRuleApplier create(FidelityRule rule) {
            return null;
        }
    }

    /**
     * An {@link InputStream} that fails every read, to reach the {@code loadSchema} catch.
     */
    private static final class ThrowingInputStream extends InputStream {

        /**
         * Fails the single-byte read.
         *
         * @return Never returns.
         * @throws IOException Always.
         */
        @Override
        public int read() throws IOException {
            throw new IOException("boom");
        }

        /**
         * Fails the bulk read used by {@code loadSchema}.
         *
         * @return Never returns.
         * @throws IOException Always.
         */
        @Override
        public byte[] readAllBytes() throws IOException {
            throw new IOException("boom");
        }
    }

    /**
     * A class loader that redefines {@link ThrowingResourceFactory} in its own namespace and
     * hands out a {@link ThrowingInputStream} for any resource, so the factory's schema read
     * fails with an {@link IOException}.
     */
    private static final class ThrowingClassLoader extends ClassLoader {

        /**
         * Builds the loader delegating to the given parent for every other class.
         *
         * @param parent The parent loader.
         */
        ThrowingClassLoader(ClassLoader parent) {
            super(parent);
        }

        /**
         * Defines the throwing factory locally and delegates everything else to the parent.
         *
         * @param name    The binary class name.
         * @param resolve Whether to link the class.
         * @return The loaded class.
         * @throws ClassNotFoundException When the parent cannot load a delegated class.
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.equals(THROWING_FACTORY_NAME)) {
                        byte[] bytes = readBytecode(name);
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else {
                        loaded = getParent().loadClass(name);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        /**
         * Returns a stream that always throws, regardless of the requested resource.
         *
         * @param name The resource name; ignored.
         * @return A {@link ThrowingInputStream}, never null.
         */
        @Override
        public InputStream getResourceAsStream(String name) {
            return new ThrowingInputStream();
        }

        /**
         * Reads the compiled bytecode of the named class from the parent loader.
         *
         * @param name The binary class name.
         * @return The class bytes.
         * @throws ClassNotFoundException When the bytecode cannot be read.
         */
        private byte[] readBytecode(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    /**
     * Holds the given monitor, signals it is held, and keeps it until asked to release.
     *
     * @param lock    The monitor to hold.
     * @param held    Counted down once the monitor is held.
     * @param release Awaited before releasing the monitor.
     */
    private static void holdMonitor(Object lock, CountDownLatch held, CountDownLatch release) {
        synchronized (lock) {
            held.countDown();
            awaitQuietly(release);
        }
    }

    /**
     * Awaits a latch, restoring the interrupt flag on interruption.
     *
     * @param latch The latch to await.
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------
    // schemaResourcePath (§12)
    // --------------------------------------------------

    /**
     * The resource path follows the {@code /schemas/rule/<TYPE>.json} convention.
     */
    @Test
    @DisplayName("schemaResourcePath follows the type convention")
    void schemaResourcePathByConvention() {
        TestFactory factory = new TestFactory(FidelityRule.TYPE_COMMUNITY_EARN);
        assertEquals("/schemas/rule/COMMUNITY_EARN.json", factory.schemaResourcePath());
    }

    // --------------------------------------------------
    // getSchema — load, cache, missing, read error
    // --------------------------------------------------

    /**
     * A first read loads the present classpath schema: local {@code null} (no early return),
     * inner {@code cachedSchema == null} true (load), resource {@code in != null}.
     */
    @Test
    @DisplayName("getSchema loads the present schema resource")
    void getSchemaLoadsPresentResource() {
        TestFactory factory = new TestFactory(FidelityRule.TYPE_COMMUNITY_EARN);
        String schema = factory.getSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"$schema\""));
    }

    /**
     * A second read returns the cached instance without reloading: the {@code local != null}
     * true arm returns the same string reference.
     */
    @Test
    @DisplayName("getSchema caches the schema after the first read")
    void getSchemaCachesAfterFirstLoad() {
        TestFactory factory = new TestFactory(FidelityRule.TYPE_COMMUNITY_EARN);
        String first = factory.getSchema();
        String second = factory.getSchema();
        assertSame(first, second);
    }

    /**
     * A rule type with no shipped schema fails loudly: the {@code in == null} true arm raises
     * an {@link IllegalStateException} naming the type and path.
     */
    @Test
    @DisplayName("getSchema fails loudly on a missing resource")
    void getSchemaMissingResourceThrows() {
        TestFactory factory = new TestFactory("NO_SUCH_TYPE");
        IllegalStateException error = assertThrows(IllegalStateException.class, factory::getSchema);
        assertTrue(error.getMessage().contains("NO_SUCH_TYPE"));
        assertTrue(error.getMessage().contains("/schemas/rule/NO_SUCH_TYPE.json"));
    }

    /**
     * A resource stream that fails to read reaches the catch: the {@code IOException} is
     * wrapped in an {@link IllegalStateException} carrying the cause. The factory class is
     * redefined by {@link ThrowingClassLoader} so its {@code getResourceAsStream} throws,
     * while its rule type points at a schema that does exist (proving it is a read error,
     * {@code in != null}, not a missing resource).
     *
     * @throws Exception When the reflective setup fails.
     */
    @Test
    @DisplayName("getSchema wraps an IOException from the resource stream")
    void getSchemaWrapsIoException() throws Exception {
        ThrowingClassLoader loader = new ThrowingClassLoader(getClass().getClassLoader());
        Class<?> type = loader.loadClass(THROWING_FACTORY_NAME);
        Object factory = type.getDeclaredConstructor().newInstance();
        Method getSchema = type.getMethod("getSchema");
        InvocationTargetException wrapper =
                assertThrows(InvocationTargetException.class, () -> getSchema.invoke(factory));
        Throwable cause = wrapper.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertTrue(cause.getMessage().contains("Failed to read"));
        assertTrue(cause.getCause() instanceof IOException);
    }

    /**
     * The double-checked second read is exercised: a caller reads {@code local == null} (no
     * early return), blocks entering the monitor another thread holds, and once the field is
     * injected and the monitor released, the inner {@code cachedSchema == null} false arm
     * returns the injected value without reloading. Determinism comes from spinning until the
     * caller is {@link Thread.State#BLOCKED} on the monitor before the injection.
     *
     * @throws Exception When the reflective field access or thread joins fail.
     */
    @Test
    @DisplayName("getSchema returns a value cached by another thread under the lock")
    void getSchemaDoubleCheckedSecondRead() throws Exception {
        TestFactory factory = new TestFactory(FidelityRule.TYPE_COMMUNITY_EARN);
        Field field = AbstractEarnRuleApplierFactory.class.getDeclaredField("cachedSchema");
        field.setAccessible(true);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> holdMonitor(factory, lockHeld, release));
        holder.start();
        lockHeld.await();
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(factory.getSchema()));
        caller.start();
        while (caller.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }
        field.set(factory, "INJECTED");
        release.countDown();
        caller.join();
        holder.join();
        assertEquals("INJECTED", result.get());
    }
}
