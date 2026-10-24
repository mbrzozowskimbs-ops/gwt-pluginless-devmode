package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Properties;

import com.google.gwt.dev.About;

/**
 * Compares the gwt-dev version on the classpath with the one the bridge is built with. {@link PluginlessDevMode} relies
 * on gwt-dev internals (the class rewriter in {@link JsoNestRepair}, the class loading in
 * {@link PlatformJavaClasses}), which may change between GWT releases; a different version may work, but failures
 * then come from the mismatch rather than from the application.
 */
final class GwtVersionCheck {

    static final String BUILD_PROPERTIES = "META-INF/pluginless-devmode/bridge.properties";

    private GwtVersionCheck() {
    }

    /**
     * Prints a warning to {@code System.err} when the versions differ.
     */
    static void warnOnMismatch() {
        mismatch(builtWith(), About.getGwtVersionNum()).ifPresent(System.err::println);
    }

    /**
     * Describes a version mismatch.
     *
     * @param builtWith gwt-dev version the bridge is built with, or {@code null} when unknown
     * @param running   gwt-dev version on the classpath; GWT reports {@code 0.0.0} when it cannot read it
     * @return the warning, or empty when the versions match or one of them is unknown
     */
    static Optional<String> mismatch(String builtWith, String running) {
        if (builtWith == null || builtWith.isBlank() || running == null || running.equals("0.0.0")
                || builtWith.equals(running)) {
            return Optional.empty();
        }
        return Optional.of("WARNING: Pluginless DevMode is built with GWT " + builtWith + ", but gwt-dev " + running
                + " is on the classpath. Use a Pluginless DevMode build for GWT " + running + " (or gwt-dev " + builtWith
                + "): PluginlessDevMode depends on gwt-dev internals.");
    }

    /**
     * Reads the gwt-dev version the bridge is built with.
     *
     * @return the version, or {@code null} when the build properties are missing
     */
    static String builtWith() {
        try (InputStream in = GwtVersionCheck.class.getClassLoader().getResourceAsStream(BUILD_PROPERTIES)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("gwt.version");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + BUILD_PROPERTIES, e);
        }
    }
}
