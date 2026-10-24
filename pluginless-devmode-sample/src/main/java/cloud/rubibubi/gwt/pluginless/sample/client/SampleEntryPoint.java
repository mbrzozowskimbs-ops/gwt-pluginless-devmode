package cloud.rubibubi.gwt.pluginless.sample.client;

import java.util.function.BooleanSupplier;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Style;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;

/**
 * Exercises the paths Classic DevMode depends on (PoC stages 2-5): Java to JS calls, JS to Java calls,
 * nested calls, exceptions crossing the boundary, a burst of DOM operations and private access inside the nest of a
 * JavaScriptObject subtype ({@code Style.Unit}, see {@code JsoNestRepair}) and a JRE class of a platform module
 * ({@code java.sql.Timestamp}, see {@code PlatformJavaClasses}).
 * Every check also runs once from {@link #onModuleLoad()}, and its result lands in an element with id
 * {@code sample-result} so that a headless browser can verify the whole round trip.
 */
public class SampleEntryPoint implements EntryPoint {

    private static final int NESTING_DEPTH = 20;
    private static final int DOM_OPERATIONS = 10_000;

    private final FlowPanel log = new FlowPanel();

    @Override
    public void onModuleLoad() {
        RootPanel root = RootPanel.get();
        root.add(new Label("Pluginless DevMode: GWT Classic DevMode without a browser plugin"));
        root.add(button("Java → JS: window width", this::windowWidth));
        root.add(button("Java → JS → Java (" + NESTING_DEPTH + " levels)", this::nestedCalls));
        root.add(button("Exceptions across the Java/JS boundary", this::exceptions));
        root.add(button(DOM_OPERATIONS + " DOM operations", this::domBurst));
        root.add(button("Style.Unit (nestmates JSO)", this::jsoNestmates));
        root.add(button("java.sql.Timestamp (platform module)", this::platformClass));
        log.setStyleName("log");
        root.add(log);

        log("onModuleLoad on " + (GWT.isScript() ? "JavaScript (compiled)" : "JVM (Classic DevMode)"));
        boolean ok = windowWidth() && nestedCalls() && exceptions() && domBurst() && jsoNestmates() && platformClass();
        DivElement result = Document.get().createDivElement();
        result.setId("sample-result");
        result.setInnerText(ok ? "SAMPLE-OK" : "SAMPLE-FAILED");
        Document.get().getBody().appendChild(result);
    }

    @PackagePrivateMarker
    private boolean windowWidth() {
        int width = Window.getClientWidth();
        log("Window.getClientWidth() = " + width);
        return width > 0;
    }

    private boolean nestedCalls() {
        int depth = descendThroughJs(NESTING_DEPTH);
        log("Java → JS → Java: reached depth " + depth);
        return depth == NESTING_DEPTH;
    }

    private boolean exceptions() {
        boolean jsCaught = false;
        try {
            throwFromJs("error from JS");
        } catch (JavaScriptException e) {
            jsCaught = e.getMessage().contains("error from JS");
        }
        boolean javaCaught = false;
        try {
            callThrowingJavaThroughJs();
        } catch (IllegalStateException e) {
            javaCaught = "error from Java".equals(e.getMessage());
        }
        log("JS exception caught in Java: " + jsCaught + ", Java exception passed through JS: " + javaCaught);
        return jsCaught && javaCaught;
    }

    private boolean domBurst() {
        long start = System.currentTimeMillis();
        DivElement container = Document.get().createDivElement();
        for (int i = 0; i < DOM_OPERATIONS; i++) {
            container.appendChild(Document.get().createDivElement());
        }
        int count = container.getChildCount();
        log(DOM_OPERATIONS + " × appendChild(createDiv): " + (System.currentTimeMillis() - start) + " ms");
        return count == DOM_OPERATIONS;
    }

    /**
     * {@code Style.Unit} constants with a body call the private constructor of the enum, which nests in the
     * JavaScriptObject {@code Style}; without the nestmate repair this throws {@link IllegalAccessError}.
     */
    private boolean jsoNestmates() {
        log.getElement().getStyle().setPaddingLeft(4, Style.Unit.PX);
        String padding = log.getElement().getStyle().getPaddingLeft();
        log("Style.setPaddingLeft(4, Style.Unit.PX) → " + padding);
        return "4px".equals(padding);
    }

    /**
     * {@code java.sql} lives in a platform module since Java 9; without {@code PlatformJavaClasses} DevMode tries to
     * define the GWT emulation and fails with {@code SecurityException: Prohibited package name: java.sql}.
     */
    private boolean platformClass() {
        java.sql.Timestamp timestamp = new java.sql.Timestamp(1_000L);
        log("new java.sql.Timestamp(1000).getTime() = " + timestamp.getTime());
        return timestamp.getTime() == 1_000L;
    }

    private void log(String message) {
        log.add(new Label(message));
    }

    private static Button button(String text, BooleanSupplier check) {
        return new Button(text, (ClickHandler) event -> check.getAsBoolean());
    }

    /**
     * Recurses by bouncing between Java and JavaScript; returns the number of Java frames entered.
     */
    static int descend(int remaining) {
        return remaining == 0 ? 0 : 1 + descendThroughJs(remaining - 1);
    }

    private static native int descendThroughJs(int remaining) /*-{
        if (remaining == 0) {
            return 0;
        }
        return @cloud.rubibubi.gwt.pluginless.sample.client.SampleEntryPoint::descend(I)(remaining);
    }-*/;

    private static native void throwFromJs(String message) /*-{
        throw new Error(message);
    }-*/;

    static void throwFromJava() {
        throw new IllegalStateException("error from Java");
    }

    private static native void callThrowingJavaThroughJs() /*-{
        @cloud.rubibubi.gwt.pluginless.sample.client.SampleEntryPoint::throwFromJava()();
    }-*/;
}
