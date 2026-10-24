package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.gwt.dev.shell.BrowserChannel;
import com.google.gwt.dev.shell.BrowserChannelException;

/**
 * Code server side of a BrowserChannel connection, scripted by tests with gwt-dev's own message classes.
 * Received messages are returned as plain records with values in their bridge JSON form, because the message
 * classes are only accessible to BrowserChannel subclasses.
 */
final class FakeCodeServer extends BrowserChannel {

    record LoadModule(String url, String sessionKey, String moduleName, String userAgent) {
    }

    record Returned(boolean exception, Object value) {
    }

    record Invoked(int dispatchId, Object thisValue, List<Object> args) {
    }

    record Special(SessionHandler.SpecialDispatchId dispatchId, List<Object> args) {
    }

    private FakeCodeServer(Socket socket) throws IOException {
        super(socket, new Refs());
    }

    static FakeCodeServer accept(ServerSocket serverSocket) throws IOException {
        return new FakeCodeServer(serverSocket.accept());
    }

    /**
     * Answers CHECK_VERSIONS with protocol 3 and reads LOAD_MODULE.
     */
    LoadModule handshake() throws Exception {
        expect(MessageType.CHECK_VERSIONS);
        CheckVersionsMessage versions = CheckVersionsMessage.receive(this);
        assertThat(versions.getMinVersion()).isEqualTo(3);
        new ProtocolVersionMessage(this, 3).send();
        expect(MessageType.LOAD_MODULE);
        LoadModuleMessage load = LoadModuleMessage.receive(this);
        return new LoadModule(load.getUrl(), load.getSessionKey(), load.getModuleName(), load.getUserAgent());
    }

    void refuseVersion(String error) throws Exception {
        expect(MessageType.CHECK_VERSIONS);
        CheckVersionsMessage.receive(this);
        new FatalErrorMessage(this, error).send();
        // FatalErrorMessage.send() does not flush; BrowserChannelServer flushes in its message loop
        getStreamToOtherSide().flush();
    }

    void requestIconAndExpectNone() throws Exception {
        RequestIconMessage.send(this);
        expect(MessageType.USER_AGENT_ICON);
        assertThat(UserAgentIconMessage.receive(this).getIconBytes()).isNull();
    }

    void sendJsni(String jsni) throws IOException {
        LoadJsniMessage.send(this, jsni);
    }

    void sendInvoke(String methodName, Value thisValue, Value... args) throws IOException {
        new InvokeOnClientMessage(this, methodName, thisValue, args).send();
    }

    void sendGetProperty(int jsObjectId, String property) throws IOException {
        new InvokeSpecialMessage(this, SessionHandler.SpecialDispatchId.GetProperty,
                new Value[] {ValueCodec.intValue(jsObjectId), ValueCodec.stringValue(property)}).send();
    }

    void sendReturn(boolean isException, Value value) throws IOException {
        ReturnMessage.send(this, isException, value);
    }

    Returned expectReturn() throws Exception {
        expect(MessageType.RETURN);
        ReturnMessage message = ReturnMessage.receive(this);
        return new Returned(message.isException(), ValueCodec.encode(message.getReturnValue()));
    }

    Invoked expectInvoke() throws Exception {
        expect(MessageType.INVOKE);
        InvokeOnServerMessage message = InvokeOnServerMessage.receive(this);
        return new Invoked(message.getMethodDispatchId(), ValueCodec.encode(message.getThis()),
                ValueCodec.encodeAll(message.getArgs()));
    }

    Special expectInvokeSpecial() throws Exception {
        expect(MessageType.INVOKE_SPECIAL);
        InvokeSpecialMessage message = InvokeSpecialMessage.receive(this);
        return new Special(message.getDispatchId(), ValueCodec.encodeAll(message.getArgs()));
    }

    int[] expectFree() throws Exception {
        expect(MessageType.FREE_VALUE);
        return FreeMessage.receive(this).getIds();
    }

    void expectQuit() throws Exception {
        expect(MessageType.QUIT);
    }

    private void expect(MessageType expected) throws IOException, BrowserChannelException {
        assertThat(Message.readMessageType(getStreamFromOtherSide())).isEqualTo(expected);
    }

    static Value nullValue() {
        Value value = new Value();
        value.setNull();
        return value;
    }

    static Value undefined() {
        Value value = new Value();
        value.setUndefined();
        return value;
    }

    private static final class Refs implements ObjectRefFactory {

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
