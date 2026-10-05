package com.corelogic.bom;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

public class PrivateFilesTest {
    private static final UserPrincipal OWNER = new Principal("owner");
    private static final UserPrincipal OTHER = new Principal("other");
    private Path root;

    @After
    public void cleanUp() throws Exception {
        if (root != null && Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    public void selectsPosixOrAclAndFailsClosedWithoutEnforceablePrivateAccess() throws Exception {
        assertEquals("posix", PrivateFiles.mode(Set.of("basic", "posix", "owner", "acl")));
        assertEquals("acl", PrivateFiles.mode(Set.of("basic", "owner", "acl", "dos", "user")));
        for (Set<String> views : List.of(Set.of("basic"), Set.of("basic", "dos", "user"),
                Set.of("basic", "acl"), Set.of("basic", "owner"), Set.of("basic", "zip"))) {
            assertThrows(IOException.class, () -> PrivateFiles.mode(views));
        }
    }

    @Test
    public void acceptsOwnerOnlyAclsForCurrentUser() throws Exception {
        for (boolean directory : new boolean[] {true, false}) {
            List<AclEntry> acl = PrivateFiles.ownerOnly(OWNER, directory);
            PrivateFiles.requireOwnerOnly(OWNER, OWNER, acl);
            assertEquals(1, acl.size());
            assertEquals(EnumSet.allOf(AclEntryPermission.class), acl.get(0).permissions());
            assertEquals(directory ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class), acl.get(0).flags());
        }
        List<AclEntry> withDeny = new ArrayList<>(PrivateFiles.ownerOnly(OWNER, false));
        withDeny.add(entry(AclEntryType.DENY, OTHER, Set.of()));
        PrivateFiles.requireOwnerOnly(OWNER, OWNER, withDeny);
    }

    @Test
    public void refusesBroadInheritedEmptyOrForeignOwnedAcls() {
        List<List<AclEntry>> unsafe = List.of(
                // Empty is also how a NULL DACL (full access for everyone) is reported.
                List.of(),
                List.of(entry(AclEntryType.ALLOW, OTHER, Set.of())),
                List.of(entry(AclEntryType.ALLOW, OWNER, Set.of()), entry(AclEntryType.ALLOW, OTHER, Set.of())),
                List.of(entry(AclEntryType.ALLOW, OWNER, Set.of()),
                        entry(AclEntryType.ALLOW, OTHER, Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.INHERIT_ONLY))),
                List.of(entry(AclEntryType.ALLOW, OWNER, Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.INHERIT_ONLY))),
                List.of(entry(AclEntryType.DENY, OTHER, Set.of())),
                List.of(entry(AclEntryType.ALLOW, OWNER, Set.of()), entry(AclEntryType.AUDIT, OTHER, Set.of())));
        for (List<AclEntry> acl : unsafe) {
            assertThrows(acl.toString(), IOException.class, () -> PrivateFiles.requireOwnerOnly(OWNER, OWNER, acl));
        }
        assertThrows(IOException.class, () ->
                PrivateFiles.requireOwnerOnly(OWNER, OTHER, PrivateFiles.ownerOnly(OTHER, true)));
        assertThrows(IOException.class, () ->
                PrivateFiles.requireOwnerOnly(null, OWNER, PrivateFiles.ownerOnly(OWNER, true)));
    }

    @Test
    public void createsAndValidatesPrivateEntriesOnThisPlatform() throws Exception {
        assumeTrue("Requires POSIX permissions or ACLs", privateCapable());
        root = Path.of("target", "private-files-" + UUID.randomUUID());
        Path directory = root.resolve("nested").resolve("cache");
        PrivateFiles.createDirectories(directory);
        PrivateFiles.createDirectories(directory);
        PrivateFiles.checkDirectory(directory);
        Path file = directory.resolve("entry.json");
        PrivateFiles.createFile(file);
        Files.writeString(file, "{}");
        PrivateFiles.checkFile(file);
        assertThrows(FileAlreadyExistsException.class, () -> PrivateFiles.createFile(file));
        assertThrows(IOException.class, () -> PrivateFiles.checkFile(directory));
        assertThrows(IOException.class, () -> PrivateFiles.checkDirectory(file));
        if (posix()) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            UserPrincipal owner = view.getOwner();
            assertTrue(view.getAcl().stream().allMatch(entry ->
                    entry.type() != AclEntryType.ALLOW || owner.equals(entry.principal())));
        }
        broaden(file);
        assertThrows(IOException.class, () -> PrivateFiles.checkFile(file));
        broaden(directory);
        assertThrows(IOException.class, () -> PrivateFiles.checkDirectory(directory));
        assertThrows(IOException.class, () -> PrivateFiles.createDirectories(directory));
    }

    @Test
    public void aclFilesIgnoreInheritedBroadAccessButPlainChildrenAreRefused() throws Exception {
        assumeTrue("Requires ACL file system without POSIX permissions", !posix() && privateCapable());
        root = Path.of("target", "private-files-" + UUID.randomUUID());
        Path directory = root.resolve("cache");
        PrivateFiles.createDirectories(directory);
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        acl.add(entry(AclEntryType.ALLOW, everyone(directory),
                Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.INHERIT_ONLY)));
        view.setAcl(acl);
        Path inherited = Files.createFile(directory.resolve("inherited.json"));
        assertThrows(IOException.class, () -> PrivateFiles.checkFile(inherited));
        Path secured = directory.resolve("secured.json");
        PrivateFiles.createFile(secured);
        PrivateFiles.checkFile(secured);
        assertThrows(IOException.class, () -> PrivateFiles.checkDirectory(directory));
    }

    @Test
    public void refusesSymbolicLinksForEntriesAndAncestors() throws Exception {
        assumeTrue("Requires POSIX permissions or ACLs", privateCapable());
        root = Path.of("target", "private-files-" + UUID.randomUUID());
        Path directory = root.resolve("cache");
        PrivateFiles.createDirectories(directory);
        Path file = directory.resolve("entry.json");
        PrivateFiles.createFile(file);
        Path fileLink = directory.resolve("link.json");
        Path directoryLink = root.resolve("linked");
        try {
            Files.createSymbolicLink(fileLink, file.getFileName());
            Files.createSymbolicLink(directoryLink, directory.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeNoException("Symbolic links unavailable", e);
        }
        assertThrows(IOException.class, () -> PrivateFiles.checkFile(fileLink));
        assertThrows(IOException.class, () -> PrivateFiles.checkDirectory(directoryLink));
        assertThrows(IOException.class, () -> PrivateFiles.checkFile(directoryLink.resolve("entry.json")));
        assertThrows(IOException.class, () -> PrivateFiles.createFile(directoryLink.resolve("new.json")));
        assertThrows(IOException.class, () -> PrivateFiles.createDirectories(directoryLink.resolve("child")));
        assertFalse(Files.exists(directory.resolve("new.json")));
        assertFalse(Files.exists(directory.resolve("child")));
    }

    static boolean posix() {
        return Path.of("target").getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    static boolean privateCapable() {
        Set<String> views = Path.of("target").getFileSystem().supportedFileAttributeViews();
        return views.contains("posix") || (views.contains("acl") && views.contains("owner"));
    }

    /** Grants other users read access using the platform's native permission model. */
    static void broaden(Path path) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        acl.add(entry(AclEntryType.ALLOW, everyone(path), Set.of()));
        view.setAcl(acl);
    }

    private static UserPrincipal everyone(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName("Everyone");
    }

    private static AclEntry entry(AclEntryType type, UserPrincipal principal, Set<AclEntryFlag> flags) {
        return AclEntry.newBuilder().setType(type).setPrincipal(principal)
                .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)
                .setFlags(flags.isEmpty() ? EnumSet.noneOf(AclEntryFlag.class) : EnumSet.copyOf(flags))
                .build();
    }

    private record Principal(String name) implements UserPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }
}
