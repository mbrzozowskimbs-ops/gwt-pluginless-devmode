package cloud.rubibubi.gwt.pluginless.bridge;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.dev.ModuleHandle;
import com.google.gwt.dev.shell.BrowserChannelServer;
import com.google.gwt.dev.shell.BrowserWidgetHost;
import com.google.gwt.dev.shell.CompilingClassLoader;
import com.google.gwt.dev.shell.ModuleSpace;
import com.google.gwt.dev.shell.ModuleSpaceHost;

/**
 * {@link BrowserWidgetHost} that installs {@link NestRepairingClassRewriter} into the class loader of every module
 * right after DevMode creates it, before the module loads its first class.
 */
final class NestRepairingBrowserWidgetHost implements BrowserWidgetHost {

    private final BrowserWidgetHost delegate;

    NestRepairingBrowserWidgetHost(BrowserWidgetHost delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModuleHandle createModuleLogger(String moduleName, String userAgent, String url, String tabKey,
            String sessionKey, BrowserChannelServer serverChannel, byte[] userAgentIcon) {
        return delegate.createModuleLogger(moduleName, userAgent, url, tabKey, sessionKey, serverChannel,
                userAgentIcon);
    }

    @Override
    public ModuleSpaceHost createModuleSpaceHost(ModuleHandle module, String moduleName)
            throws UnableToCompleteException {
        return new NestRepairingModuleSpaceHost(delegate.createModuleSpaceHost(module, moduleName));
    }

    private static final class NestRepairingModuleSpaceHost implements ModuleSpaceHost {

        private final ModuleSpaceHost delegate;

        NestRepairingModuleSpaceHost(ModuleSpaceHost delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onModuleReady(ModuleSpace space) throws UnableToCompleteException {
            // Creates the CompilingClassLoader; ModuleSpace starts loading classes only after this returns
            delegate.onModuleReady(space);
            NestRepairingClassRewriter.install(delegate.getClassLoader());
        }

        @Override
        public String[] getEntryPointTypeNames() {
            return delegate.getEntryPointTypeNames();
        }

        @Override
        public CompilingClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }

        @Override
        public TreeLogger getLogger() {
            return delegate.getLogger();
        }

        @Override
        public String rebind(TreeLogger logger, String sourceTypeName) throws UnableToCompleteException {
            return delegate.rebind(logger, sourceTypeName);
        }
    }
}
