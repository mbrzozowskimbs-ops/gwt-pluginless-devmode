package cloud.rubibubi.gwt.pluginless.linker;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.linker.LinkerOrder;
import com.google.gwt.core.ext.linker.Shardable;
import com.google.gwt.core.linker.CrossSiteIframeLinker;

/**
 * Cross-site iframe linker whose Classic DevMode bootstrap script ({@code <module>.devmode.js}) starts with the
 * Pluginless DevMode bridge client, so DevMode works without a browser plugin.
 * <p>
 * Everything else is produced exactly like by {@link CrossSiteIframeLinker}. The module
 * {@code cloud.rubibubi.gwt.pluginless.PluginlessDevMode} registers this class under the linker name {@code xsiframe}, which keeps all
 * {@code <when-linker-added name="xsiframe"/>} rules of GWT in effect.
 */
@LinkerOrder(LinkerOrder.Order.PRIMARY)
@Shardable
public class BridgeLinker extends CrossSiteIframeLinker {

    static final String DEVMODE_TEMPLATE = "com/google/gwt/core/ext/linker/impl/devmode.js";
    static final String BRIDGE_CLIENT = "cloud/rubibubi/gwt/pluginless/linker/devmode-bridge.js";

    @Override
    public String getDescription() {
        return "Pluginless DevMode (Cross-Site-Iframe with plugin-less Classic DevMode)";
    }

    @Override
    protected StringBuffer readFileToStringBuffer(String filename, TreeLogger logger)
            throws UnableToCompleteException {
        StringBuffer content = super.readFileToStringBuffer(filename, logger);
        if (DEVMODE_TEMPLATE.equals(filename)) {
            content.insert(0, super.readFileToStringBuffer(BRIDGE_CLIENT, logger).append('\n'));
        }
        return content;
    }
}
