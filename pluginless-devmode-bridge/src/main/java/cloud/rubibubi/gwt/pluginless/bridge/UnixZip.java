package cloud.rubibubi.gwt.pluginless.bridge;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a zip archive keeping the Unix file modes stored by Unix zip tools: symbolic links and permissions such as
 * the executable bit. {@link ZipFile} reads the entries but does not expose these modes, which the central directory
 * keeps in the high 16 bits of the external attributes of entries made on Unix.
 * <p>
 * The Electron builds for macOS need both: {@code Electron.app} contains framework symlinks
 * ({@code Versions/Current}) and executables. Zip64 archives are not supported.
 * <p>
 * Symbolic links are checked only lexically, so a chain of them may still lead outside the target directory; no entry
 * is therefore written through an already unpacked link. Official Electron archives store every file under its real
 * path, so they never need to.
 */
final class UnixZip {

    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int CENTRAL_DIRECTORY_ENTRY = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIZE = 22;
    private static final int MAX_COMMENT_SIZE = 0xFFFF;
    private static final int CENTRAL_DIRECTORY_ENTRY_SIZE = 46;
    private static final int MADE_BY_UNIX = 3;
    private static final int FILE_TYPE_MASK = 0170000;
    private static final int SYMBOLIC_LINK = 0120000;

    private UnixZip() {
    }

    /**
     * Unpacks the archive into an empty or missing directory.
     *
     * @param zip    archive
     * @param target directory to unpack into
     * @throws IOException when the archive cannot be read, is a Zip64 archive, has an entry or a symbolic link
     *                     pointing outside the target directory, or has an entry under an unpacked symbolic link
     */
    static void unpack(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Map<String, Integer> modes = unixModes(zip);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Files.createDirectories(root);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                Path path = inside(root, root.resolve(entry.getName()), entry.getName());
                notThroughSymbolicLink(root, path, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                    continue;
                }
                Files.createDirectories(path.getParent());
                int mode = modes.getOrDefault(entry.getName(), 0);
                try (InputStream in = archive.getInputStream(entry)) {
                    if ((mode & FILE_TYPE_MASK) == SYMBOLIC_LINK) {
                        String link = new String(in.readAllBytes(), UTF_8);
                        inside(root, path.getParent().resolve(link), entry.getName() + " -> " + link);
                        Files.createSymbolicLink(path, Path.of(link));
                        continue;
                    }
                    Files.copy(in, path);
                }
                if (posix && (mode & 0777) != 0) {
                    Files.setPosixFilePermissions(path, permissions(mode));
                }
            }
        }
    }

    /**
     * Reads the Unix modes of the entries made on Unix from the central directory.
     *
     * @param zip archive
     * @return entry name to Unix mode (file type and permission bits)
     * @throws IOException when the archive cannot be read or is a Zip64 archive
     */
    static Map<String, Integer> unixModes(Path zip) throws IOException {
        try (FileChannel channel = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = channel.size();
            int tailSize = (int) Math.min(size, END_OF_CENTRAL_DIRECTORY_SIZE + MAX_COMMENT_SIZE);
            ByteBuffer tail = read(channel, size - tailSize, tailSize);
            int end = -1;
            for (int i = tailSize - END_OF_CENTRAL_DIRECTORY_SIZE; i >= 0 && end < 0; i--) {
                if (tail.getInt(i) == END_OF_CENTRAL_DIRECTORY) {
                    end = i;
                }
            }
            if (end < 0) {
                throw new IOException("Not a zip archive: " + zip);
            }
            int entries = Short.toUnsignedInt(tail.getShort(end + 10));
            long directorySize = Integer.toUnsignedLong(tail.getInt(end + 12));
            long directoryOffset = Integer.toUnsignedLong(tail.getInt(end + 16));
            if (entries == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
                throw new IOException("Zip64 archives are not supported: " + zip);
            }

            ByteBuffer directory = read(channel, directoryOffset, (int) directorySize);
            Map<String, Integer> modes = new HashMap<>();
            int position = 0;
            for (int i = 0; i < entries; i++) {
                if (directory.getInt(position) != CENTRAL_DIRECTORY_ENTRY) {
                    throw new IOException("Corrupt central directory in " + zip);
                }
                int madeBy = Short.toUnsignedInt(directory.getShort(position + 4)) >> 8;
                int nameLength = Short.toUnsignedInt(directory.getShort(position + 28));
                int extraLength = Short.toUnsignedInt(directory.getShort(position + 30));
                int commentLength = Short.toUnsignedInt(directory.getShort(position + 32));
                int externalAttributes = directory.getInt(position + 38);
                byte[] name = new byte[nameLength];
                directory.get(position + CENTRAL_DIRECTORY_ENTRY_SIZE, name);
                if (madeBy == MADE_BY_UNIX) {
                    // UTF-8 like ZipFile, so that the names match its entries
                    modes.put(new String(name, UTF_8), externalAttributes >>> 16);
                }
                position += CENTRAL_DIRECTORY_ENTRY_SIZE + nameLength + extraLength + commentLength;
            }
            return modes;
        }
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("Unexpected end of zip archive");
            }
        }
        return buffer;
    }

    private static Path inside(Path root, Path path, String entry) throws IOException {
        Path normalized = path.normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Zip entry points outside " + root + ": " + entry);
        }
        return normalized;
    }

    private static void notThroughSymbolicLink(Path root, Path path, String entry) throws IOException {
        Path current = root;
        for (Path name : root.relativize(path)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Zip entry is under a symbolic link: " + entry);
            }
        }
    }

    private static Set<PosixFilePermission> permissions(int mode) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] bits = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
        for (int i = 0; i < bits.length; i++) {
            if ((mode & (1 << i)) != 0) {
                permissions.add(bits[i]);
            }
        }
        return permissions;
    }
}
