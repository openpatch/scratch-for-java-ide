package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Versions of a project's code to go back to: after every run that worked
 * (the window drew for a few seconds without a crash) the IDE keeps a version.
 * Restoring keeps the current code first, so a restore can itself be undone.
 *
 * <p>Stored as Git commits in the project's {@code .git}, under private
 * {@code refs/scratch4j/versions/} refs. Each commit is independent, so dropping
 * an old version releases its objects for Git garbage collection. Neither HEAD
 * nor the user's index, branches or commits are changed. Automatic run versions
 * keep code and maps; named versions keep the project, including binary assets.
 */
public final class ProjectSnapshots {

  /** Why a version was kept. */
  public enum Kind { RAN, BEFORE_RESTORE, MANUAL }

  public enum Scope { CODE, PROJECT }

  /** One immutable Git version: where, when, why, and its files. */
  public record Version(Path gitDir, String refName, String commitId, Instant time, Kind kind,
                        String title, Scope scope) {

    public boolean automatic() { return kind != Kind.MANUAL; }

    /** Text view for code versions. Use contents() when restoring files. */
    public Map<String, String> files() throws IOException {
      Map<String, String> out = new TreeMap<>();
      contents().forEach((path, bytes) -> out.put(path, new String(bytes, StandardCharsets.UTF_8)));
      return out;
    }

    public Map<String, byte[]> contents() throws IOException {
      Map<String, byte[]> out = new TreeMap<>();
      try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile())
          .setMustExist(true).build();
          RevWalk walk = new RevWalk(repo);
          TreeWalk tree = new TreeWalk(repo)) {
        tree.addTree(walk.parseCommit(ObjectId.fromString(commitId)).getTree());
        tree.setRecursive(true);
        while (tree.next()) {
          out.put(tree.getPathString(), repo.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes());
        }
      }
      return out;
    }
  }

  /** Null on either side means that the file did not exist there. */
  public record Change(byte[] then, byte[] now) {
    public boolean binary() {
      return (then != null && RawText.isBinary(then)) || (now != null && RawText.isBinary(now));
    }

    public String thenText() { return then == null ? null : new String(then, StandardCharsets.UTF_8); }
    public String nowText() { return now == null ? null : new String(now, StandardCharsets.UTF_8); }
  }

  private static final String REF_PREFIX = "refs/scratch4j/versions/";
  private static final Set<String> KEPT = Set.of(".java", ".tmx", ".tsx", ".frag", ".vert",
      ".glsl");
  private static final int MAX_AUTOMATIC_VERSIONS = 40;
  private static final Set<String> GENERATED = Set.of(".git", "build", "target", "out", "export");
  private static final Set<String> CACHES = Set.of("build", "history", "editor", "versions", "trash", "log");
  private static final Comparator<Version> NEWEST_FIRST = Comparator.comparing(Version::time)
      .thenComparing(Version::refName).reversed();

  private ProjectSnapshots() {}

  /** The project's code files now (project path -> text). */
  public static Map<String, String> current(Path root) throws IOException {
    Map<String, String> out = new TreeMap<>();
    current(root, Scope.CODE).forEach((path, bytes) -> out.put(path, new String(bytes, StandardCharsets.UTF_8)));
    return out;
  }

  private static Map<String, byte[]> current(Path root, Scope scope) throws IOException {
    Map<String, byte[]> out = new TreeMap<>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        String path = root.relativize(dir).toString();
        boolean skip = scope == Scope.CODE ? Set.of(".scratch4j", "+libs", ".git").contains(path)
            : GENERATED.contains(path) || (dir.getParent() != null
                && dir.getParent().equals(root.resolve(".scratch4j"))
                && CACHES.contains(dir.getFileName().toString()));
        return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        String name = file.getFileName().toString();
        if (GENERATED.contains(root.relativize(file).toString())) return FileVisitResult.CONTINUE;
        int dot = name.lastIndexOf('.');
        boolean kept = scope == Scope.PROJECT ? !name.endsWith(".class") && !name.endsWith(".ctxt")
            : dot >= 0 && KEPT.contains(name.substring(dot).toLowerCase(Locale.ROOT));
        if (kept && Files.isRegularFile(file)) {
          out.put(root.relativize(file).toString().replace('\\', '/'),
              Files.readAllBytes(file));
        }
        return FileVisitResult.CONTINUE;
      }
    });
    return out;
  }

  /**
   * Keeps the current code as a version, unless it equals the newest one.
   * Returns the new version, or null when nothing changed.
   */
  public static synchronized Version keep(Path root, Kind kind) throws IOException {
    return keep(root, kind, kind == Kind.MANUAL ? "Version" : automaticTitle(kind),
        kind == Kind.RAN ? Scope.CODE : Scope.PROJECT);
  }

  /** A named milestone includes every project change, with no staging step. */
  public static synchronized Version keep(Path root, String title) throws IOException {
    if (title == null || title.isBlank()) throw new IOException("A version needs a name");
    return keep(root, Kind.MANUAL, title.strip(), Scope.PROJECT);
  }

  private static Version keep(Path root, Kind kind, String title, Scope scope) throws IOException {
    Map<String, byte[]> files = current(root, scope);
    try (Repository repo = open(root, true)) {
      migrate(root, repo);
      List<Version> versions = list(repo);
      if (kind != Kind.MANUAL && !versions.isEmpty() && versions.getFirst().scope() == scope
          && sameContents(versions.getFirst().contents(), files)) {
        return null;
      }
      long millis = System.currentTimeMillis();
      if (!versions.isEmpty()) millis = Math.max(millis, versions.getFirst().time().toEpochMilli() + 1);
      String name = millis + "-" + kind.name().toLowerCase(Locale.ROOT);
      while (repo.exactRef(REF_PREFIX + name) != null) name += "x";
      Version kept = write(repo, name, files, title, scope);
      prune(repo);
      return kept;
    }
  }

  /** Newest first. Old folder-based versions are imported on first access. */
  public static synchronized List<Version> list(Path root) throws IOException {
    boolean legacy = !legacyDirectories(root).isEmpty();
    try (Repository repo = open(root, legacy)) {
      if (repo == null) return List.of();
      if (legacy) {
        migrate(root, repo);
        prune(repo);
      }
      return list(repo);
    }
  }

  /** The newest version kept after a run that worked, if any. */
  public static Version lastWorking(Path root) throws IOException {
    return list(root).stream().filter(v -> v.kind() == Kind.RAN).findFirst().orElse(null);
  }

  /**
   * Restores the version's files and removes files added since then, within
   * its scope. The current project is kept as a version first.
   */
  public static synchronized void restore(Path root, Version version) throws IOException {
    // Read before keeping: the version being restored may be the oldest of 40.
    Map<String, byte[]> wanted = version.contents();
    keep(root, Kind.BEFORE_RESTORE);
    for (String path : current(root, version.scope()).keySet()) {
      if (!wanted.containsKey(path)) {
        restoreFile(root, path, (byte[]) null);
      }
    }
    for (var entry : wanted.entrySet()) {
      restoreFile(root, entry.getKey(), entry.getValue());
    }
  }

  /** Puts one file back as it was in a version (kept in the per-file history). */
  public static void restoreFile(Path root, String path, String text) throws IOException {
    restoreFile(root, path, text.getBytes(StandardCharsets.UTF_8));
  }

  public static void restoreFile(Path root, String path, byte[] bytes) throws IOException {
    Path target = root.resolve(path).normalize();
    if (!target.startsWith(root.normalize())) {
      throw new IOException("Outside the project: " + path);
    }
    if (bytes != null && Files.isRegularFile(target)
        && Arrays.equals(Files.readAllBytes(target), bytes)) return;
    if (!target.startsWith(root.resolve(".scratch4j").normalize())) LocalHistory.snapshot(root, target);
    if (bytes == null) {
      Files.deleteIfExists(target);
      return;
    }
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".version-", ".tmp");
    try {
      Files.write(temp, bytes);
      AtomicFiles.replace(temp, target);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** What changed from a version to now (null contents where a file was absent). */
  public static Map<String, Change> changes(Path root, Version version) throws IOException {
    Map<String, byte[]> then = version.contents();
    Map<String, byte[]> now = current(root, version.scope());
    Map<String, Change> out = new LinkedHashMap<>();
    Set<String> paths = new java.util.TreeSet<>(then.keySet());
    paths.addAll(now.keySet());
    for (String path : paths) {
      byte[] a = then.get(path);
      byte[] b = now.get(path);
      if (!Arrays.equals(a, b)) {
        out.put(path, new Change(a, b));
      }
    }
    return out;
  }

  /** Open only this project's repository, never one in a parent directory. */
  private static Repository open(Path root, boolean create) throws IOException {
    boolean exists = Files.exists(root.resolve(".git"));
    if (!exists && !create) return null;
    Repository repo = new FileRepositoryBuilder().setWorkTree(root.toFile())
        .setMustExist(exists).build();
    if (!exists) {
      try {
        repo.create();
        repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + "main");
      } catch (IOException | RuntimeException e) {
        repo.close();
        throw e;
      }
    }
    return repo;
  }

  private static List<Version> list(Repository repo) throws IOException {
    List<Version> out = new ArrayList<>();
    for (Ref ref : repo.getRefDatabase().getRefsByPrefix(REF_PREFIX)) {
      Version version = version(repo, ref.getName(), ref.getObjectId());
      if (version != null) out.add(version);
    }
    out.sort(NEWEST_FIRST);
    return out;
  }

  private static Version version(Repository repo, String refName, ObjectId commit) throws IOException {
    String name = refName.substring(REF_PREFIX.length());
    int dash = name.indexOf('-');
    if (dash < 0) return null;
    try {
      Instant time = Instant.ofEpochMilli(Long.parseLong(name.substring(0, dash)));
      Kind kind = Kind.valueOf(name.substring(dash + 1).replaceAll("x+$", "")
          .toUpperCase(Locale.ROOT));
      String title = kind == Kind.MANUAL ? "Version" : automaticTitle(kind);
      Scope scope = Scope.CODE;
      if (!commit.equals(ObjectId.zeroId())) {
        try (RevWalk walk = new RevWalk(repo)) {
          var stored = walk.parseCommit(commit);
          if (kind == Kind.MANUAL && !stored.getShortMessage().equals("Scratch4J version: manual")) {
            title = stored.getShortMessage();
          }
          if (stored.getFullMessage().endsWith("\nScratch4J-Scope: project\n")) scope = Scope.PROJECT;
        }
      }
      return new Version(repo.getDirectory().toPath(), refName, commit.name(), time, kind, title, scope);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Build a tree in memory; using the real Git index would disturb staged edits. */
  private static Version write(Repository repo, String name, Map<String, byte[]> files,
      String title, Scope scope)
      throws IOException {
    String refName = REF_PREFIX + name;
    try (var inserter = repo.newObjectInserter()) {
      DirCache cache = DirCache.newInCore();
      DirCacheEditor editor = cache.editor();
      for (var file : files.entrySet()) {
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, file.getValue());
        editor.add(new DirCacheEditor.PathEdit(file.getKey()) {
          @Override
          public void apply(DirCacheEntry entry) {
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(blob);
          }
        });
      }
      editor.finish();
      Version metadata = version(repo, refName, ObjectId.zeroId());
      if (metadata == null) throw new IOException("Invalid version name: " + name);
      PersonIdent identity = new PersonIdent("Scratch for Java Studio", "studio@localhost",
          metadata.time(), ZoneOffset.UTC);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(cache.writeTree(inserter));
      commit.setAuthor(identity);
      commit.setCommitter(identity);
      commit.setMessage(title + "\n\nScratch4J-Scope: " + scope.name().toLowerCase(Locale.ROOT) + "\n");
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repo.updateRef(refName);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(id);
      update.disableRefLog();
      if (update.update() != RefUpdate.Result.NEW) {
        throw new IOException("Could not keep project version: " + refName);
      }
      return version(repo, refName, id);
    }
  }

  private static void prune(Repository repo) throws IOException {
    List<Version> versions = list(repo).stream().filter(Version::automatic).toList();
    for (int i = MAX_AUTOMATIC_VERSIONS; i < versions.size(); i++) {
      Version version = versions.get(i);
      RefUpdate update = repo.updateRef(version.refName());
      update.setExpectedOldObjectId(ObjectId.fromString(version.commitId()));
      update.setForceUpdate(true);
      update.disableRefLog();
      if (update.delete() != RefUpdate.Result.FORCED) {
        throw new IOException("Could not remove old project version: " + version.refName());
      }
    }
  }

  private static List<Path> legacyDirectories(Path root) throws IOException {
    Path dir = root.resolve(".scratch4j/versions");
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> children = Files.list(dir)) {
      return children.filter(Files::isDirectory).filter(p -> p.getFileName().toString()
          .matches("[0-9]+-(ran|before_restore|manual)x*")).toList();
    }
  }

  /** Import everything before removing old copies, so interrupted imports can resume. */
  private static void migrate(Path root, Repository repo) throws IOException {
    List<Path> directories = legacyDirectories(root);
    for (Path dir : directories) {
      String name = dir.getFileName().toString();
      if (repo.exactRef(REF_PREFIX + name) != null) continue;
      Map<String, byte[]> files = new TreeMap<>();
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path file : walk.filter(Files::isRegularFile).toList()) {
          files.put(dir.relativize(file).toString().replace('\\', '/'),
              Files.readAllBytes(file));
        }
      }
      Version metadata = version(repo, REF_PREFIX + name, ObjectId.zeroId());
      write(repo, name, files, metadata.title(), Scope.CODE);
    }
    for (Path dir : directories) {
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static String automaticTitle(Kind kind) {
    return "[AUTO] " + (kind == Kind.RAN ? "Ran without a crash" : "Before restoring a version");
  }

  private static boolean sameContents(Map<String, byte[]> a, Map<String, byte[]> b) {
    return a.keySet().equals(b.keySet())
        && a.entrySet().stream().allMatch(entry -> Arrays.equals(entry.getValue(), b.get(entry.getKey())));
  }
}
