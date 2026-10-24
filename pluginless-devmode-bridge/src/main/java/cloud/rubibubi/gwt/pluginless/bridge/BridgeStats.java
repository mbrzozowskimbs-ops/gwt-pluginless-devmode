package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Round trip statistics of one session, logged once per burst of activity (e.g. one click in the application).
 * <p>
 * A burst ends after {@value #IDLE_MILLIS} ms without requests. Every HTTP request of the browser is one round trip;
 * its time splits into the part spent in the bridge and the DevMode JVM (from receiving the request to sending the
 * response) and the gap until the next request, spent in the browser: running JavaScript, the synchronous XHR and
 * HTTP. A gap longer than {@value #WAIT_MILLIS} ms is not round trip overhead but waiting (for a GWT-RPC response,
 * a timer, the user) and is reported separately.
 * <p>
 * The browser reports with every request how long it has spent in each phase so far ({@code stats} message, see
 * {@code docs/PROTOCOL.md}); the difference over a burst splits the browser time into HTTP (the XHR minus the time
 * in the bridge and DevMode JVM), page JavaScript including DOM operations, loading JSNI and the client's own code.
 * HTTP splits further into the bridge's HTTP server in the DevMode JVM (reading and parsing the request, writing the
 * response) and the rest outside the JVM: the network stack and the browser's XHR.
 * Bursts of fewer than {@value #MIN_LOGGED_ROUND_TRIPS} round trips (timers, single events) are not logged.
 * <p>
 * Each burst goes to the log and, when the system property {@code pluginless.statsFile} names a file, is appended to
 * that file as well ({@link PluginlessDevMode} defaults it to {@code pluginless-devmode-stats.log} in the DevMode {@code -logdir}).
 */
final class BridgeStats {

    private static final System.Logger LOG = System.getLogger(BridgeStats.class.getName());

    // Shorter than the period of application timers, which would otherwise glue all clicks into one burst
    private static final long IDLE_MILLIS = 300;
    private static final long IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_MILLIS);
    private static final int MIN_LOGGED_ROUND_TRIPS = 100;
    private static final long WAIT_MILLIS = 50;
    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

    static final String STATS_FILE_PROPERTY = "pluginless.statsFile";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pluginless-devmode-stats");
        thread.setDaemon(true);
        return thread;
    });

    private final String sessionId;
    private final ScheduledFuture<?> idleCheck;
    private String browser = "browser";
    private long httpNanos;

    private long burstStart;
    private long lastEnd;
    private long jvmNanos;
    private long browserNanos;
    private long waitNanos;
    private int waits;
    private int roundTrips;

    // Cumulative browser phase times in microseconds, indexed in the order of PHASES in devmode-bridge.js
    private static final int PAGE = 0;
    private static final int CLIENT = 1;
    private static final int XHR = 2;
    private static final int PAGE_JS = 3;
    private static final int JSNI = 4;
    private static final int PHASES = 5;
    private long[] browserTotals = new long[PHASES];
    private long[] burstStartTotals = new long[PHASES];
    private int javaToJs;
    private int jsToJava;

    BridgeStats(String sessionId) {
        this.sessionId = sessionId;
        this.idleCheck = SCHEDULER.scheduleWithFixedDelay(this::logIfIdle, IDLE_MILLIS / 2, IDLE_MILLIS / 2,
                TimeUnit.MILLISECONDS);
    }

    synchronized void userAgent(String userAgent) {
        if (userAgent.contains("Firefox/")) {
            browser = "Firefox";
        } else if (userAgent.contains("Edg/")) {
            browser = "Edge";
        } else if (userAgent.contains("Chrome/")) {
            browser = "Chrome";
        } else if (userAgent.contains("Safari/")) {
            browser = "Safari";
        }
    }

    /**
     * Records the time the HTTP handler spent on a request (it includes the round trip time in the session).
     *
     * @param nanos time from reading the request to writing the response
     */
    synchronized void recordHttp(long nanos) {
        httpNanos += nanos;
    }

    /**
     * Stores the cumulative phase times the browser sent with the current request.
     *
     * @param micros microseconds spent in each phase since the page loaded
     */
    synchronized void browserTotals(long[] micros) {
        if (micros.length == PHASES) {
            browserTotals = micros;
        }
    }

    /**
     * Records one round trip.
     *
     * @param requestTag  tag of the last browser message of the request ({@code invoke}, {@code return}, ...)
     * @param responseTag tag of the final command of the response ({@code invoke}, {@code get}, {@code return}, ...)
     * @param start       {@link System#nanoTime()} when the bridge started handling the request
     * @param end         {@link System#nanoTime()} when the response was ready
     */
    synchronized void record(String requestTag, String responseTag, long start, long end) {
        if (roundTrips > 0 && start - lastEnd > IDLE_NANOS) {
            logBurst();
        }
        if (roundTrips == 0) {
            burstStart = start;
            burstStartTotals = browserTotals;
        } else if (start - lastEnd > WAIT_NANOS) {
            waitNanos += start - lastEnd;
            waits++;
        } else {
            browserNanos += start - lastEnd;
        }
        roundTrips++;
        jvmNanos += end - start;
        lastEnd = end;
        // A JavaScript call into Java starts with the browser; a Java call into JavaScript ends the response
        if (isCall(requestTag)) {
            jsToJava++;
        }
        if (isCall(responseTag)) {
            javaToJs++;
        }
    }

    synchronized void close() {
        idleCheck.cancel(false);
        logBurst();
    }

    private synchronized void logIfIdle() {
        if (roundTrips > 0 && System.nanoTime() - lastEnd > IDLE_NANOS) {
            logBurst();
        }
    }

    private void logBurst() {
        if (roundTrips >= MIN_LOGGED_ROUND_TRIPS) {
            double wallSeconds = (lastEnd - burstStart) / 1e9;
            double jvmMillis = jvmNanos / 1e6;
            double browserMillis = browserNanos / 1e6;
            String line = String.format(Locale.ROOT,
                    "Session %s (%s): %.1f s = round trips %.1f s + waiting %.1f s (%d gaps > %d ms); "
                            + "%d round trips (Java->JS %d, JS->Java %d), %.3f ms per round trip "
                            + "(browser %.3f ms, bridge and DevMode JVM %.3f ms)",
                    BridgeTrace.shortId(sessionId), browser, wallSeconds, (jvmMillis + browserMillis) / 1000, waitNanos / 1e9, waits, WAIT_MILLIS,
                    roundTrips, javaToJs, jsToJava, (jvmMillis + browserMillis) / roundTrips,
                    browserMillis / roundTrips, jvmMillis / roundTrips);
            line += browserBreakdown(jvmMillis);
            LOG.log(System.Logger.Level.INFO, line);
            appendToStatsFile(TIME.format(LocalDateTime.now()) + " " + line);
        }
        roundTrips = 0;
        javaToJs = 0;
        jsToJava = 0;
        jvmNanos = 0;
        httpNanos = 0;
        browserNanos = 0;
        waitNanos = 0;
        waits = 0;
    }

    private String browserBreakdown(double jvmMillis) {
        double[] millis = new double[PHASES];
        for (int i = 0; i < PHASES; i++) {
            millis[i] = (browserTotals[i] - burstStartTotals[i]) / 1000.0;
        }
        if (millis[XHR] <= 0) {
            // A client without phase statistics
            return "";
        }
        double httpServerMillis = Math.max(0, httpNanos / 1e6 - jvmMillis);
        double outsideJvmMillis = Math.max(0, millis[XHR] - httpNanos / 1e6);
        return String.format(Locale.ROOT, "; browser per round trip: HTTP %.3f ms (bridge HTTP server %.3f ms, "
                        + "network and XHR %.3f ms), page JS and DOM %.3f ms, JSNI loading %.3f ms, "
                        + "Pluginless DevMode client %.3f ms, other page code %.3f ms",
                Math.max(0, millis[XHR] - jvmMillis) / roundTrips, httpServerMillis / roundTrips,
                outsideJvmMillis / roundTrips, millis[PAGE_JS] / roundTrips, millis[JSNI] / roundTrips,
                millis[CLIENT] / roundTrips, millis[PAGE] / roundTrips);
    }

    private static void appendToStatsFile(String line) {
        String file = System.getProperty(STATS_FILE_PROPERTY);
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(file), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot append round trip statistics to " + file, e);
        }
    }

    private static boolean isCall(String tag) {
        return "invoke".equals(tag) || "get".equals(tag) || "set".equals(tag) || "getJava".equals(tag)
                || "setJava".equals(tag);
    }
}
