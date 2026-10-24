package cloud.rubibubi.gwt.pluginless.sample.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Package-private runtime annotation. DevMode builds proxies for annotations of client types, which for
 * non-public annotations only works in the class loader that loaded the annotation — the sample keeps this
 * case covered, since real applications often have such annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface PackagePrivateMarker {
}
