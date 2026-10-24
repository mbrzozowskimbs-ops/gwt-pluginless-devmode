package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Builds the classes in the shape {@code HostedModeClassRewriter} leaves them for a JavaScriptObject subtype
 * {@code nest/Outer} with nested classes {@code Outer$Inner} and {@code Outer$Unit}: synthetic interface
 * {@code Outer} without nest attributes, implementation {@code Outer$} with the original {@code NestMembers}, nested
 * classes naming {@code Outer} as their host. {@code Outer$Inner.call()} reads private methods of {@code Outer$}
 * and {@code Outer$Unit}, just like {@code Style$Unit$1} calls the private constructor of {@code Style$Unit}.
 */
@DisplayName("JsoNestRepair")
class JsoNestRepairTest {

    private static final String OUTER = "nest/Outer";
    private static final String IMPL = OUTER + "$";
    private static final String INNER = OUTER + "$Inner";
    private static final String UNIT = OUTER + "$Unit";
    private static final Predicate<String> IS_JSO_INTF = Set.of(OUTER, "nest/Child")::contains;

    @Test
    @DisplayName("Should reproduce IllegalAccessError for unrepaired classes")
    void shouldFailWithoutRepair() {
        // given
        Map<String, byte[]> classes = Map.of(
                OUTER, rewrittenIntf(),
                IMPL, rewrittenImpl(),
                INNER, inner(),
                UNIT, unit());

        // when / then
        assertThatThrownBy(() -> callInner(classes))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalAccessError.class)
                .hasMessageContaining("nest member");
    }

    @Test
    @DisplayName("Should allow private access inside the nest of a JavaScriptObject subtype after repair")
    void shouldAllowPrivateAccessAfterRepair() throws Exception {
        // given
        Map<String, byte[]> classes = Map.of(
                OUTER, JsoNestRepair.repairJsoIntf(rewrittenIntf(), original(), IS_JSO_INTF),
                IMPL, JsoNestRepair.repairRewrittenClass(rewrittenImpl(), true, IS_JSO_INTF),
                INNER, JsoNestRepair.repairRewrittenClass(inner(), false, IS_JSO_INTF),
                UNIT, JsoNestRepair.repairRewrittenClass(unit(), false, IS_JSO_INTF));

        // when
        Object result = callInner(classes);

        // then
        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("Should make the synthetic interface the nest host of its implementation and nested classes")
    void shouldMakeInterfaceNestHost() {
        // when
        byte[] intf = JsoNestRepair.repairJsoIntf(rewrittenIntf(), original(), IS_JSO_INTF);
        byte[] impl = JsoNestRepair.repairRewrittenClass(rewrittenImpl(), true, IS_JSO_INTF);

        // then
        assertThat(nestOf(intf).members).containsExactlyInAnyOrder(IMPL, INNER, UNIT);
        assertThat(nestOf(impl).host).isEqualTo(OUTER);
        assertThat(nestOf(impl).members).isEmpty();
    }

    @Test
    @DisplayName("Should list the implementation of a nested JavaScriptObject subtype in the host")
    void shouldListNestedJsoImpl() {
        // given
        byte[] host = classWith("nest/Host", writer -> writer.visitNestMember("nest/Child"));

        // when
        byte[] repaired = JsoNestRepair.repairRewrittenClass(host, false, IS_JSO_INTF);

        // then
        assertThat(nestOf(repaired).members).containsExactly("nest/Child", "nest/Child$");
    }

    @Test
    @DisplayName("Should permit the implementation of a JavaScriptObject subclass in a sealed implementation")
    void shouldPermitJsoSubclassImpl() {
        // given
        byte[] sealedImpl = classWith(IMPL, writer -> writer.visitPermittedSubclass("nest/Child"));

        // when
        byte[] repaired = JsoNestRepair.repairRewrittenClass(sealedImpl, true, IS_JSO_INTF);

        // then
        assertThat(nestOf(repaired).permitted).containsExactly("nest/Child$");
    }

    @Test
    @DisplayName("Should leave classes older than Java 11 untouched")
    void shouldLeaveJava8ClassesUntouched() {
        // given
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "nest/Legacy", null, "java/lang/Object", null);
        writer.visitEnd();
        byte[] legacy = writer.toByteArray();

        // when / then
        assertThat(JsoNestRepair.repairRewrittenClass(legacy, false, IS_JSO_INTF)).isSameAs(legacy);
        assertThat(JsoNestRepair.repairJsoIntf(legacy, null, IS_JSO_INTF)).isSameAs(legacy);
    }

    private static Object callInner(Map<String, byte[]> classes) throws Exception {
        ClassLoader loader = new BytesClassLoader(classes);
        return loader.loadClass(INNER.replace('/', '.')).getMethod("call").invoke(null);
    }

    /**
     * Output of {@code writeJsoIntf}: Java 5 interface without nest attributes and, like GWT writes it, without
     * {@code ACC_ABSTRACT}.
     */
    private static byte[] rewrittenIntf() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE, OUTER, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Original class compiled from source, the input of {@code writeJsoIntf}.
     */
    private static byte[] original() {
        return classWith(OUTER, writer -> {
            writer.visitNestMember(INNER);
            writer.visitNestMember(UNIT);
        });
    }

    /**
     * Output of {@code rewrite} for the implementation: original code and nest members under the name {@code Outer$}.
     */
    private static byte[] rewrittenImpl() {
        return classWith(IMPL, writer -> {
            writer.visitNestMember(INNER);
            writer.visitNestMember(UNIT);
            returnConstant(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", 40);
        });
    }

    private static byte[] unit() {
        return classWith(UNIT, writer -> {
            writer.visitNestHost(OUTER);
            returnConstant(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "value", 2);
        });
    }

    /**
     * {@code public static int call() { return Outer$.secret() + Outer$Unit.value(); }}
     */
    private static byte[] inner() {
        return classWith(INNER, writer -> {
            writer.visitNestHost(OUTER);
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I", null,
                    null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC, IMPL, "secret", "()I", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, UNIT, "value", "()I", false);
            method.visitInsn(Opcodes.IADD);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(2, 0);
            method.visitEnd();
        });
    }

    private static void returnConstant(ClassWriter writer, int access, String name, int value) {
        MethodVisitor method = writer.visitMethod(access, name, "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }

    private static byte[] classWith(String name, Consumer<ClassWriter> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        body.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Nest nestOf(byte[] classBytes) {
        Nest nest = new Nest();
        new ClassReader(classBytes).accept(nest, 0);
        return nest;
    }

    private static final class Nest extends ClassVisitor {

        private String host;
        private final List<String> members = new ArrayList<>();
        private final List<String> permitted = new ArrayList<>();

        Nest() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitNestHost(String nestHost) {
            host = nestHost;
        }

        @Override
        public void visitNestMember(String nestMember) {
            members.add(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            permitted.add(permittedSubclass);
        }
    }

    private static final class BytesClassLoader extends ClassLoader {

        private final Map<String, byte[]> classes = new HashMap<>();

        BytesClassLoader(Map<String, byte[]> classes) {
            super(JsoNestRepairTest.class.getClassLoader());
            classes.forEach((name, bytes) -> this.classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
