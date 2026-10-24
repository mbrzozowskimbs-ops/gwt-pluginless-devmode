package cloud.rubibubi.gwt.pluginless.bridge;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.gwt.dev.shell.BrowserChannel;
import com.google.gwt.dev.shell.BrowserChannelException;

/**
 * One browser page connected to the GWT code server through the bridge.
 * <p>
 * The browser speaks a synchronous request/response protocol over HTTP (see {@code docs/PROTOCOL.md}); this class
 * translates every request into BrowserChannel messages on a dedicated TCP connection to the code server, then reads
 * the code server's messages until one that the browser has to answer or that completes the request
 * ({@code invoke}, {@code get}, {@code set}, {@code return}, {@code quit}, {@code error}). Asynchronous messages read
 * on the way ({@code jsni}, {@code free}) precede that final command in the response.
 * <p>
 * The browser is single threaded and blocks on each request, so requests of one session never overlap; access is
 * still synchronized to keep a misbehaving client from interleaving messages on the socket.
 */
final class BridgeSession extends BrowserChannel {

    private static final int PROTOCOL_VERSION = BrowserChannel.PROTOCOL_VERSION_CURRENT;

    private final String id;
    private final BridgeTrace trace;
    private final BridgeStats stats;
    private boolean closed;

    private BridgeSession(String id, Socket socket, BridgeTrace trace) throws IOException {
        super(socket, new RefFactory());
        this.id = id;
        this.trace = trace;
        this.stats = new BridgeStats(id);
    }

    /**
     * Opens a TCP connection to the code server for a new browser session.
     *
     * @param id    session id chosen by the browser
     * @param host  code server host
     * @param port  code server port ({@code -codeServerPort} of DevMode)
     * @param trace protocol trace output
     * @return connected session that still has to receive the {@code connect} message
     * @throws IOException when the code server cannot be reached
     */
    static BridgeSession open(String id, String host, int port, BridgeTrace trace) throws IOException {
        Socket socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        return new BridgeSession(id, socket, trace);
    }

    /**
     * Processes a batch of browser messages. All messages but the last must be asynchronous ({@code free}).
     *
     * @param messages browser messages in sending order
     * @return commands for the browser produced by the last message
     * @throws IOException             when the connection to the code server fails; the session is closed
     * @throws BridgeProtocolException when a message is malformed or the code server violates the protocol
     */
    synchronized List<Object> handle(List<?> messages) throws IOException, BridgeProtocolException {
        if (closed) {
            throw new BridgeProtocolException("Session " + id + " is closed");
        }
        long start = System.nanoTime();
        List<Object> commands = List.of();
        String requestTag = null;
        try {
            for (Object json : messages) {
                List<?> message = Messages.message(json);
                trace.request(id, message);
                requestTag = Messages.tag(message);
                commands = dispatch(message);
            }
        } catch (IOException | BridgeProtocolException | RuntimeException e) {
            close();
            throw e;
        }
        trace.response(id, commands);
        String responseTag = commands.isEmpty() ? null : Messages.tag((List<?>) commands.get(commands.size() - 1));
        stats.record(requestTag, responseTag, start, System.nanoTime());
        return commands;
    }

    void recordHttp(long nanos) {
        stats.recordHttp(nanos);
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Ends the code server connection without notifying the code server.
     */
    synchronized void close() {
        if (!closed) {
            closed = true;
            stats.close();
            endSession();
        }
    }

    private List<Object> dispatch(List<?> message) throws IOException, BridgeProtocolException {
        switch (Messages.tag(message)) {
            case "free" -> {
                FreeMessage.send(this, toIntArray(Messages.list(message, 1)));
                return List.of();
            }
            case "stats" -> {
                stats.browserTotals(toLongArray(Messages.list(message, 1)));
                return List.of();
            }
            case "quit" -> {
                QuitMessage.send(this);
                close();
                return List.of();
            }
            default -> {
                request(message).send();
                return pump();
            }
        }
    }

    /**
     * Translates a browser message that the code server answers.
     */
    private Message request(List<?> message) throws IOException, BridgeProtocolException {
        return switch (Messages.tag(message)) {
            case "connect" -> {
                stats.userAgent(Messages.string(message, 6));
                checkVersions(Messages.string(message, 1));
                yield new LoadModuleMessage(this, Messages.string(message, 2), Messages.string(message, 3),
                        Messages.string(message, 4), Messages.string(message, 5), Messages.string(message, 6));
            }
            case "invoke" -> new InvokeOnServerMessage(this, Messages.integer(message, 1),
                    ValueCodec.decode(Messages.element(message, 2)),
                    ValueCodec.decodeAll(Messages.list(message, 3)));
            case "getJava" -> new InvokeSpecialMessage(this, SessionHandler.SpecialDispatchId.GetProperty,
                    new Value[] {
                            ValueCodec.intValue(Messages.integer(message, 1)),
                            ValueCodec.intValue(Messages.integer(message, 2))});
            case "setJava" -> new InvokeSpecialMessage(this, SessionHandler.SpecialDispatchId.SetProperty,
                    new Value[] {
                            ValueCodec.intValue(Messages.integer(message, 1)),
                            ValueCodec.intValue(Messages.integer(message, 2)),
                            ValueCodec.decode(Messages.element(message, 3))});
            case "return" -> new ReturnMessage(this, Messages.bool(message, 1),
                    ValueCodec.decode(Messages.element(message, 2)));
            default -> throw new BridgeProtocolException("Unknown browser message: " + Messages.tag(message));
        };
    }

    private void checkVersions(String hostedHtmlVersion) throws IOException, BridgeProtocolException {
        new CheckVersionsMessage(this, PROTOCOL_VERSION, PROTOCOL_VERSION, hostedHtmlVersion).send();
        MessageType type = readMessageType();
        if (type == MessageType.FATAL_ERROR) {
            throw new BridgeProtocolException("Code server refused the connection: "
                    + FatalErrorMessage.receive(this).getError());
        }
        if (type != MessageType.PROTOCOL_VERSION) {
            throw new BridgeProtocolException("Expected PROTOCOL_VERSION from code server, got " + type);
        }
        int version = ProtocolVersionMessage.receive(this).getProtocolVersion();
        if (version != PROTOCOL_VERSION) {
            throw new BridgeProtocolException("Unsupported BrowserChannel protocol version " + version);
        }
    }

    /**
     * Reads code server messages until one that ends the current browser request.
     */
    private List<Object> pump() throws IOException, BridgeProtocolException {
        List<Object> commands = new ArrayList<>();
        while (true) {
            MessageType type = readMessageType();
            switch (type) {
                case LOAD_JSNI -> commands.add(Messages.of("jsni", LoadJsniMessage.receive(this).getJsni()));
                case FREE_VALUE -> commands.add(Messages.of("free", Arrays.stream(FreeMessage.receive(this).getIds()).boxed().toList()));
                case REQUEST_ICON -> {
                    RequestIconMessage.receive(this);
                    UserAgentIconMessage.send(this, null);
                }
                case INVOKE -> {
                    InvokeOnClientMessage invoke = InvokeOnClientMessage.receive(this);
                    commands.add(Messages.of("invoke", invoke.getMethodName(),
                            ValueCodec.encode(invoke.getThis()), ValueCodec.encodeAll(invoke.getArgs())));
                    return commands;
                }
                case INVOKE_SPECIAL -> {
                    List<Object> command = jsPropertyCommand(receiveInvokeSpecial());
                    if (command != null) {
                        commands.add(command);
                        return commands;
                    }
                }
                case RETURN -> {
                    ReturnMessage result = ReturnMessage.receive(this);
                    commands.add(Messages.of("return", result.isException(),
                            ValueCodec.encode(result.getReturnValue())));
                    return commands;
                }
                case QUIT -> {
                    close();
                    commands.add(Messages.of("quit"));
                    return commands;
                }
                case FATAL_ERROR -> {
                    String error = FatalErrorMessage.receive(this).getError();
                    close();
                    commands.add(Messages.of("error", "Code server fatal error: " + error));
                    return commands;
                }
                default -> throw new BridgeProtocolException("Unexpected message from code server: " + type);
            }
        }
    }

    private InvokeSpecialMessage receiveInvokeSpecial() throws IOException, BridgeProtocolException {
        try {
            return InvokeSpecialMessage.receive(this);
        } catch (BrowserChannelException e) {
            throw new BridgeProtocolException("Invalid INVOKE_SPECIAL from code server", e);
        }
    }

    /**
     * Maps a code server property request on a JavaScript object to a browser command; other special methods are
     * answered here with an exception, as the original plugins did.
     *
     * @return the browser command, or {@code null} when the request was answered directly
     */
    private List<Object> jsPropertyCommand(InvokeSpecialMessage special) throws IOException {
        Value[] args = special.getArgs();
        List<Object> command = switch (special.getDispatchId()) {
            case GetProperty -> args.length == 2 ? Messages.of("get", args[0].getInt(), propertyKey(args[1])) : null;
            case SetProperty -> args.length == 3
                    ? Messages.of("set", args[0].getInt(), propertyKey(args[1]), ValueCodec.encode(args[2])) : null;
            default -> null;
        };
        if (command != null) {
            return command;
        }
        ReturnMessage.send(this, true,
                ValueCodec.stringValue("Unsupported invokeSpecial " + special.getDispatchId() + " in browser"));
        return null;
    }

    private static Object propertyKey(Value key) {
        return key.isString() ? key.getString() : (Object) key.getInt();
    }

    private MessageType readMessageType() throws IOException, BridgeProtocolException {
        try {
            return Message.readMessageType(getStreamFromOtherSide());
        } catch (BrowserChannelException e) {
            throw new BridgeProtocolException("Invalid message from code server", e);
        }
    }

    private static int[] toIntArray(List<?> json) throws BridgeProtocolException {
        int[] ids = new int[json.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = Messages.integer(json, i);
        }
        return ids;
    }

    private static long[] toLongArray(List<?> json) throws BridgeProtocolException {
        long[] values = new long[json.size()];
        for (int i = 0; i < values.length; i++) {
            if (!(json.get(i) instanceof Long value)) {
                throw new BridgeProtocolException("Element " + i + " must be an integer in " + json);
            }
            values[i] = value;
        }
        return values;
    }

    /**
     * The bridge only forwards object ids; it never tracks object lifetimes itself.
     */
    private static final class RefFactory implements ObjectRefFactory {

        @Override
        public JavaObjectRef getJavaObjectRef(int refId) {
            return new JavaObjectRef(refId);
        }

        @Override
        public JsObjectRef getJsObjectRef(int refId) {
            return new JsObjectRef(refId);
        }

        @Override
        public Set<Integer> getRefIdsForCleanup() {
            return Collections.emptySet();
        }
    }
}
