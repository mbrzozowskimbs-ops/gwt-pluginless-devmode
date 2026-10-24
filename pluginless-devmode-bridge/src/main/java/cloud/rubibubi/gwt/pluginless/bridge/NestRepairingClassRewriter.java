package cloud.rubibubi.gwt.pluginless.bridge;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.shell.CompilingClassLoader;
import com.google.gwt.dev.shell.rewrite.HostedModeClassRewriter;

/**
 * {@link HostedModeClassRewriter} that passes every rewritten class through {@link JsoNestRepair}.
 * <p>
 * {@link CompilingClassLoader} creates its rewriter internally and keeps it in a private field, so
 * {@link #install(CompilingClassLoader)} replaces that field with this decorator before the loader defines any class.
 */
final class NestRepairingClassRewriter extends HostedModeClassRewriter {

    private final HostedModeClassRewriter delegate;

    private NestRepairingClassRewriter(HostedModeClassRewriter delegate) {
        // All state lives in the delegate; every public method is overridden
        super(Set.of(), Map.of(), null, null);
        this.delegate = delegate;
    }

    /**
     * Wraps the rewriter of the given class loader; a loader without JavaScriptObject types has no rewriter and is
     * left as is.
     *
     * @param loader class loader of a module that has not loaded any class yet
     */
    static void install(CompilingClassLoader loader) {
        try {
            Field field = CompilingClassLoader.class.getDeclaredField("classRewriter");
            field.setAccessible(true);
            HostedModeClassRewriter rewriter = (HostedModeClassRewriter) field.get(loader);
            if (rewriter != null && !(rewriter instanceof NestRepairingClassRewriter)) {
                field.set(loader, new NestRepairingClassRewriter(rewriter));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot install the nestmate repair into " + loader
                    + "; does gwt-dev still keep the rewriter in CompilingClassLoader.classRewriter?", e);
        }
    }

    @Override
    public boolean isJsoImpl(String className) {
        return delegate.isJsoImpl(className);
    }

    @Override
    public boolean isJsoIntf(String className) {
        return delegate.isJsoIntf(className);
    }

    @Override
    public byte[] rewrite(TypeOracle typeOracle, String className, byte[] classBytes,
            Map<String, String> anonymousClassMap) {
        byte[] rewritten = delegate.rewrite(typeOracle, className, classBytes, anonymousClassMap);
        return JsoNestRepair.repairRewrittenClass(rewritten, delegate.isJsoImpl(className), this::isJsoIntfName);
    }

    @Override
    public byte[] writeJsoIntf(String className, byte[] classBytes) {
        byte[] intf = delegate.writeJsoIntf(className, classBytes);
        return JsoNestRepair.repairJsoIntf(intf, classBytes, this::isJsoIntfName);
    }

    private boolean isJsoIntfName(String internalName) {
        return delegate.isJsoIntf(internalName.replace('/', '.'));
    }
}
