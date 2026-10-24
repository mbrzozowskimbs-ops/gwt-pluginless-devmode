package cloud.rubibubi.gwt.pluginless.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Restores consistent nestmate attributes ({@code NestHost}/{@code NestMembers}, Java 11+) in classes rewritten by
 * GWT's {@code HostedModeClassRewriter}.
 * <p>
 * DevMode splits every {@code JavaScriptObject} subtype {@code C} into a synthetic interface {@code C} and an
 * implementation class {@code C$} that holds the original code. The rewriter predates nestmates: the interface is
 * written without nest attributes and {@code C$} keeps the {@code NestMembers} of {@code C}, while the nested classes
 * still name {@code C} as their host. The JVM then rejects every private access inside such a nest with
 * {@link IllegalAccessError} (e.g. {@code Style$Unit$1} calling the private constructor of {@code Style$Unit}).
 * <p>
 * The repair makes the interface {@code C} the nest host again:
 * <ul>
 *     <li>the interface gets the nest attributes of the original {@code C} and lists {@code C$} as a member,</li>
 *     <li>{@code C$} becomes a member of {@code C} (or of the original host when {@code C} was nested itself),</li>
 *     <li>a host listing a JavaScriptObject subtype {@code M} also lists {@code M$},</li>
 *     <li>{@code C$} permits {@code M$} instead of {@code M}, since the subclass of {@code C$} is {@code M$}.</li>
 * </ul>
 * Class names are internal names ({@code com/google/gwt/dom/client/Style}).
 */
final class JsoNestRepair {

    private static final int NESTMATES_VERSION = Opcodes.V11;

    private JsoNestRepair() {
    }

    /**
     * Repairs a class produced by {@code HostedModeClassRewriter.rewrite}.
     *
     * @param classBytes rewritten class
     * @param jsoImpl    whether the class is a JavaScriptObject implementation {@code C$}
     * @param isJsoIntf  tells whether an internal class name denotes a JavaScriptObject subtype
     * @return repaired class, or {@code classBytes} when there is nothing to repair
     */
    static byte[] repairRewrittenClass(byte[] classBytes, boolean jsoImpl, Predicate<String> isJsoIntf) {
        ClassReader reader = new ClassReader(classBytes);
        if (majorVersion(reader) < NESTMATES_VERSION) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new RewrittenClassVisitor(writer, jsoImpl, isJsoIntf), 0);
        return writer.toByteArray();
    }

    /**
     * Repairs a synthetic interface produced by {@code HostedModeClassRewriter.writeJsoIntf}.
     *
     * @param intfBytes     synthetic interface {@code C}
     * @param originalBytes original class {@code C} compiled from source, may be {@code null}
     * @param isJsoIntf     tells whether an internal class name denotes a JavaScriptObject subtype
     * @return repaired interface, or {@code intfBytes} when the original class does not belong to a nest
     */
    static byte[] repairJsoIntf(byte[] intfBytes, byte[] originalBytes, Predicate<String> isJsoIntf) {
        if (originalBytes == null) {
            return intfBytes;
        }
        ClassReader original = new ClassReader(originalBytes);
        NestCollector nest = new NestCollector();
        original.accept(nest, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (nest.host == null && nest.members.isEmpty()) {
            return intfBytes;
        }

        ClassReader reader = new ClassReader(intfBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                // Nest attributes are ignored in class files older than Java 11; since Java 6 an interface must
                // also be abstract, which the Java 5 interface written by GWT is not
                super.visit(Math.max(version, majorVersion(original)), access | Opcodes.ACC_ABSTRACT, name,
                        signature, superName, interfaces);
                if (nest.host != null) {
                    super.visitNestHost(nest.host);
                } else {
                    super.visitNestMember(name + '$');
                    for (String member : nest.members) {
                        visitMemberWithImpl(this, member, isJsoIntf);
                    }
                }
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int majorVersion(ClassReader reader) {
        return reader.readUnsignedShort(6);
    }

    private static void visitMemberWithImpl(ClassVisitor visitor, String member, Predicate<String> isJsoIntf) {
        visitor.visitNestMember(member);
        if (isJsoIntf.test(member)) {
            visitor.visitNestMember(member + '$');
        }
    }

    private static final class RewrittenClassVisitor extends ClassVisitor {

        private final boolean jsoImpl;
        private final Predicate<String> isJsoIntf;
        private String name;
        private boolean nestHost;

        RewrittenClassVisitor(ClassVisitor next, boolean jsoImpl, Predicate<String> isJsoIntf) {
            super(Opcodes.ASM9, next);
            this.jsoImpl = jsoImpl;
            this.isJsoIntf = isJsoIntf;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            this.name = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitNestMember(String member) {
            if (jsoImpl) {
                // The members now belong to the synthetic interface, see repairJsoIntf
                nestHost = true;
            } else {
                visitMemberWithImpl(cv, member, isJsoIntf);
            }
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            boolean jsoSubclass = jsoImpl && isJsoIntf.test(permittedSubclass);
            super.visitPermittedSubclass(jsoSubclass ? permittedSubclass + '$' : permittedSubclass);
        }

        @Override
        public void visitEnd() {
            if (nestHost) {
                super.visitNestHost(name.substring(0, name.length() - 1));
            }
            super.visitEnd();
        }
    }

    private static final class NestCollector extends ClassVisitor {

        private String host;
        private final List<String> members = new ArrayList<>();

        NestCollector() {
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
    }
}
