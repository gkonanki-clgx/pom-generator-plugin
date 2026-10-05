package com.corelogic.bom;

import com.sun.security.auth.module.NTSystem;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and validates owner-only files and directories for private local data.
 *
 * <p>POSIX stores require exactly {@code 0700} directories and {@code 0600} files. ACL stores (Windows)
 * require the current user to own the entry and every ALLOW entry to grant only that owner; inherited
 * broad entries, empty/NULL DACLs and other owners are refused. Stores with neither capability fail closed.
 * Symbolic links and other reparse points are refused for the entry and every existing ancestor.
 */
public final class PrivateFiles {
    static final String POSIX = "posix";
    static final String ACL = "acl";
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";
    private static final Map<FileSystem, UserPrincipal> USERS = new ConcurrentHashMap<>();

    private PrivateFiles() {}

    /** Creates the directory (and missing parents) privately if absent, then validates it. */
    public static void createDirectories(Path directory) throws IOException {
        Path path = directory.toAbsolutePath().normalize();
        refuseLinkedAncestors(path);
        String mode = mode(path.getFileSystem().supportedFileAttributeViews());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            UserPrincipal user = ACL.equals(mode) ? currentUser(path.getFileSystem()) : null;
            FileAttribute<?> attribute = initialAttribute(mode, user, true);
            Path parent = path.getParent();
            // Only the leaf is guaranteed and validated as private; missing parents merely start restricted.
            if (parent != null) {
                Files.createDirectories(parent, attribute);
            }
            try {
                Files.createDirectory(path, attribute);
                if (user != null) {
                    restrict(path, user, true);
                }
            } catch (FileAlreadyExistsException e) {
                // Concurrently created entries are validated, never modified.
            }
        }
        checkDirectory(path);
    }

    /** Creates a new private regular file; fails if it already exists. Content is written by the caller. */
    public static void createFile(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        refuseLinkedAncestors(path);
        String mode = mode(path.getFileSystem().supportedFileAttributeViews());
        UserPrincipal user = ACL.equals(mode) ? currentUser(path.getFileSystem()) : null;
        Files.createFile(path, initialAttribute(mode, user, false));
        if (user != null) {
            restrict(path, user, false);
        }
        checkFile(path);
    }

    public static void checkDirectory(Path directory) throws IOException {
        check(directory.toAbsolutePath().normalize(), true);
    }

    public static void checkFile(Path file) throws IOException {
        check(file.toAbsolutePath().normalize(), false);
    }

    private static void check(Path path, boolean directory) throws IOException {
        refuseLinkedAncestors(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("Private path is not a plain " + (directory ? "directory" : "file"));
        }
        String mode = mode(path.getFileSystem().supportedFileAttributeViews());
        if (!Files.getFileStore(path).supportsFileAttributeView(mode)) {
            throw new IOException("Private file permissions unsupported by file store");
        }
        if (POSIX.equals(mode)) {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
                    PosixFilePermissions.fromString(directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS))) {
                throw new IOException("Private path permissions are too broad");
            }
            return;
        }
        AclFileAttributeView view = aclView(path);
        requireOwnerOnly(currentUser(path.getFileSystem()), view.getOwner(), view.getAcl());
    }

    /** Selects the enforceable private-access model, failing closed when neither is available. */
    static String mode(Set<String> views) throws IOException {
        if (views.contains(POSIX)) {
            return POSIX;
        }
        if (views.contains(ACL) && views.contains("owner")) {
            return ACL;
        }
        throw new IOException("Private file permissions unsupported by file system");
    }

    /** Requires current-user ownership and ALLOW entries granting only that owner. */
    static void requireOwnerOnly(UserPrincipal user, UserPrincipal owner, List<AclEntry> acl) throws IOException {
        if (user == null || !user.equals(owner)) {
            throw new IOException("Private path is not owned by the current user");
        }
        // An empty list is also how a NULL DACL (unrestricted access) is reported.
        boolean ownerAllowed = false;
        for (AclEntry entry : acl) {
            if (entry.type() == AclEntryType.ALLOW) {
                if (!owner.equals(entry.principal())) {
                    throw new IOException("Private path grants access to other principals");
                }
                ownerAllowed |= !entry.flags().contains(AclEntryFlag.INHERIT_ONLY);
            } else if (entry.type() != AclEntryType.DENY) {
                throw new IOException("Private path has unsupported access entries");
            }
        }
        if (!ownerAllowed) {
            throw new IOException("Private path has no owner-only access control list");
        }
    }

    static List<AclEntry> ownerOnly(UserPrincipal user, boolean directory) {
        return List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(directory ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        : EnumSet.noneOf(AclEntryFlag.class))
                .build());
    }

    private static FileAttribute<?> initialAttribute(String mode, UserPrincipal user, boolean directory) {
        if (POSIX.equals(mode)) {
            return PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS));
        }
        List<AclEntry> acl = ownerOnly(user, directory);
        return new FileAttribute<List<AclEntry>>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    /** Replaces creation-time ACLs (which may merge inherited parent entries) with an owner-only list. */
    private static void restrict(Path path, UserPrincipal user, boolean directory) throws IOException {
        AclFileAttributeView view = aclView(path);
        if (!user.equals(view.getOwner())) {
            // Elevated Windows sessions may default ownership to the Administrators group.
            view.setOwner(user);
        }
        view.setAcl(ownerOnly(user, directory));
    }

    private static AclFileAttributeView aclView(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Private file permissions unsupported by file system");
        }
        return view;
    }

    private static void refuseLinkedAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Private path must not contain symbolic links");
            }
            if (!current.equals(path) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
                throw new IOException("Private path must not contain reparse points");
            }
        }
    }

    private static UserPrincipal currentUser(FileSystem fileSystem) throws IOException {
        UserPrincipal user = USERS.get(fileSystem);
        if (user == null) {
            String name = System.getProperty("user.name");
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                try {
                    // Domain-qualified token identity avoids ambiguous lookup of a bare account name.
                    NTSystem system = new NTSystem();
                    name = system.getDomain() + "\\" + system.getName();
                } catch (RuntimeException | LinkageError e) {
                    throw new IOException("Current user unavailable", e);
                }
            }
            if (name == null || name.isEmpty()) {
                throw new IOException("Current user unavailable");
            }
            user = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
            USERS.put(fileSystem, user);
        }
        return user;
    }
}
