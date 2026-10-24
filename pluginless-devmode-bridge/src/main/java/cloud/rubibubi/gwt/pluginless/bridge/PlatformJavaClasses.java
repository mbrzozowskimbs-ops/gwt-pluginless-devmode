package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gwt.dev.shell.CompilingClassLoader;

/**
 * Exposes the {@code java.*} classes of platform modules (e.g. {@code java.sql.Timestamp}) to client code.
 * <p>
 * {@link CompilingClassLoader} looks JRE classes up in a class loader without parent, which since Java 9 sees only
 * the boot modules. For a class of a platform module such as {@code java.sql} it falls back to the GWT emulation and
 * fails with {@code SecurityException: Prohibited package name}, since only the JDK may define {@code java.*}
 * classes. That exception also leaves {@code CompilingClassLoader} believing it is still injecting JSNI, so no JSNI
 * of any class loaded later reaches the browser ({@code window[methodName] is undefined}).
 * <p>
 * {@code CompilingClassLoader} serves classes from its static {@code BRIDGE_CLASS_NAMES} map before compiling them;
 * adding the platform classes there gives client code the JDK classes, as on Java 8 where they were boot classes.
 */
final class PlatformJavaClasses {

    private PlatformJavaClasses() {
    }

    /**
     * Adds every {@code java.*} class of the platform modules to the bridge classes of {@link CompilingClassLoader}.
     */
    static void install() {
        // Runs before DevMode loads any module, so no CompilingClassLoader reads the map concurrently
        platformJavaClasses().forEach(bridgeClassNames()::putIfAbsent);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Class<?>> bridgeClassNames() {
        try {
            Field field = CompilingClassLoader.class.getDeclaredField("BRIDGE_CLASS_NAMES");
            field.setAccessible(true);
            return (Map<String, Class<?>>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot expose platform classes to client code; does gwt-dev still keep "
                    + "them in CompilingClassLoader.BRIDGE_CLASS_NAMES?", e);
        }
    }

    private static Map<String, Class<?>> platformJavaClasses() {
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        Map<String, Class<?>> classes = new HashMap<>();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (Module module : ModuleLayer.boot().modules()) {
            if (module.getClassLoader() != platform) {
                continue;
            }
            Path packages = jrt.getPath("/modules", module.getName(), "java");
            if (!Files.isDirectory(packages)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(packages)) {
                files.map(Path::toString)
                        .filter(file -> file.endsWith(".class"))
                        .map(file -> file.substring(("/modules/" + module.getName() + "/").length(),
                                file.length() - ".class".length()).replace('/', '.'))
                        .forEach(name -> classes.put(name, load(name, platform)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return classes;
    }

    private static Class<?> load(String name, ClassLoader platform) {
        try {
            return Class.forName(name, false, platform);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Platform class " + name + " listed in jrt:/ but not loadable", e);
        }
    }
}
