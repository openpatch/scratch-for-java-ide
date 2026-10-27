package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ctrl+P: find a project file by typing letters of its path in order (as in
 * VS Code: {@code plsp} finds {@code PlayerSprite.java}) and open it. Letters
 * in the file name, at the start of a word and next to each other count
 * most; with nothing typed, the files open in tabs come first.
 */
final class QuickOpen {

  private static final int SHOWN = 50;
  private static final int MAX_FILES = 20_000;
  /** Folders that hold no files to open: build output, metadata, VCS. */
  private static final Set<String> SKIPPED = Set.of("target", "build", "out", "bin",
      "node_modules");

  /** A file that matches, with its score and the matched positions in {@code path}. */
  record Match(Path file, String path, int score, List<Integer> positions) {}

  private QuickOpen() {}

  /** The project's files, as paths relative to {@code root} with '/' (hidden folders left out). */
  static List<Path> projectFiles(Path root) {
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
          if (!dir.equals(root) && (name.startsWith(".") || SKIPPED.contains(name))) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          String name = file.getFileName().toString();
          if (!name.startsWith(".") && !name.endsWith(".class")) {
            files.add(file);
          }
          return files.size() < MAX_FILES ? FileVisitResult.CONTINUE
              : FileVisitResult.TERMINATE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      // what was found so far
    }
    return files;
  }

  static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  /**
   * How well {@code query} matches {@code path} (letters in order, spaces
   * ignored, case too), or null. The letters are looked for in the file name
   * first, then in the whole path.
   */
  static Match match(String query, Path file, String path) {
    String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
    if (q.isEmpty()) {
      return new Match(file, path, 0, List.of());
    }
    int nameStart = path.lastIndexOf('/') + 1;
    List<Integer> positions = find(q, path, nameStart);
    int bonus = 0;
    if (positions != null) {
      bonus = 30;
      if (path.toLowerCase(Locale.ROOT).startsWith(q, nameStart)) {
        bonus += 40;
      }
    } else {
      positions = find(q, path, 0);
      if (positions == null) {
        return null;
      }
    }
    int score = bonus;
    for (int i = 0; i < positions.size(); i++) {
      int at = positions.get(i);
      score += 1;
      if (i > 0 && positions.get(i - 1) == at - 1) {
        score += 5;
      }
      if (wordStart(path, at)) {
        score += 8;
      }
    }
    // shorter paths first among equals
    score = score * 1000 - Math.min(999, path.length());
    return new Match(file, path, score, positions);
  }

  /**
   * The positions of {@code q}'s letters in {@code path} from {@code from} on:
   * each letter at the next word start if one comes before the next plain
   * match would end the chance, else at its next occurrence.
   */
  private static List<Integer> find(String q, String path, int from) {
    String lower = path.toLowerCase(Locale.ROOT);
    List<Integer> positions = new ArrayList<>();
    int at = from;
    for (int i = 0; i < q.length(); i++) {
      char c = q.charAt(i);
      int next = lower.indexOf(c, at);
      if (next < 0) {
        return null;
      }
      // prefer a word start further on ("ps" in "PlayerSprite" takes the S of Sprite)
      boolean afterMatch = !positions.isEmpty() && positions.getLast() == next - 1;
      if (!wordStart(path, next) && !afterMatch) {
        for (int k = next + 1; k < lower.length(); k++) {
          if (lower.charAt(k) == c && wordStart(path, k)) {
            next = k;
            break;
          }
        }
      }
      positions.add(next);
      at = next + 1;
    }
    return positions;
  }

  /** The first letter of a word: after / - _ . or a space, or a capital after a small letter. */
  private static boolean wordStart(String path, int at) {
    if (at == 0) {
      return true;
    }
    char before = path.charAt(at - 1);
    char c = path.charAt(at);
    return "/-_. ".indexOf(before) >= 0
        || Character.isUpperCase(c) && Character.isLowerCase(before);
  }

  /**
   * The best {@value #SHOWN} files for {@code query}; for an empty query the
   * {@code recent} files first, then all by path.
   */
  static List<Match> search(String query, Path root, List<Path> files, List<Path> recent) {
    if (query.isBlank()) {
      Set<Path> ordered = new LinkedHashSet<>();
      recent.stream().filter(files::contains).forEach(ordered::add);
      files.stream().sorted(Comparator.comparing(f -> relative(root, f))).forEach(ordered::add);
      return ordered.stream().limit(SHOWN)
          .map(f -> new Match(f, relative(root, f), 0, List.of())).toList();
    }
    List<Match> matches = new ArrayList<>();
    for (Path file : files) {
      Match match = match(query, file, relative(root, file));
      if (match != null) {
        matches.add(match);
      }
    }
    matches.sort(Comparator.comparingInt(Match::score).reversed()
        .thenComparing(Match::path));
    return matches.size() > SHOWN ? List.copyOf(matches.subList(0, SHOWN)) : matches;
  }

  // --- the popup -----------------------------------------------------------------

  /** The popup under the window's title; {@code open} gets the chosen file. */
  static Popup show(Window owner, Path root, List<Path> recent, Consumer<Path> open) {
    List<Path> files = projectFiles(root);
    Popup popup = new Popup();
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);
    TextField field = new TextField();
    field.setPromptText(I18n.t("quickopen.prompt"));
    field.getStyleClass().add("quick-open-field");
    ListView<Match> list = new ListView<>();
    list.setPrefHeight(360);
    list.setFocusTraversable(false);
    list.setCellFactory(v -> new MatchCell());
    list.setPlaceholder(new Label(I18n.t("quickopen.none")));
    VBox box = new VBox(6, field, list);
    box.setPadding(new Insets(8));
    box.setPrefWidth(620);
    box.getStyleClass().add("quick-open");
    if (owner.getScene() != null) {
      box.getStylesheets().setAll(owner.getScene().getStylesheets());
      box.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass().stream()
          .filter(c -> c.equals("dark") || c.equals("high-contrast")).toList());
    }
    popup.getContent().add(box);

    Runnable update = () -> {
      list.getItems().setAll(search(field.getText(), root, files, recent));
      list.getSelectionModel().selectFirst();
      list.scrollTo(0);
    };
    Runnable choose = () -> {
      Match chosen = list.getSelectionModel().getSelectedItem();
      if (chosen != null) {
        popup.hide();
        open.accept(chosen.file());
      }
    };
    field.textProperty().addListener((o, a, b) -> update.run());
    field.setOnKeyPressed(e -> {
      int at = list.getSelectionModel().getSelectedIndex();
      if (e.getCode() == KeyCode.DOWN) {
        list.getSelectionModel().select(Math.min(list.getItems().size() - 1, at + 1));
      } else if (e.getCode() == KeyCode.UP) {
        list.getSelectionModel().select(Math.max(0, at - 1));
      } else if (e.getCode() == KeyCode.ENTER) {
        choose.run();
      } else {
        return;
      }
      list.scrollTo(Math.max(0, list.getSelectionModel().getSelectedIndex() - 4));
      e.consume();
    });
    list.setOnMouseClicked(e -> choose.run());
    update.run();
    popup.show(owner, owner.getX() + (owner.getWidth() - box.getPrefWidth()) / 2,
        owner.getY() + 70);
    field.requestFocus();
    return popup;
  }

  /** A file's icon, its name with the typed letters marked, and its folder. */
  private static final class MatchCell extends ListCell<Match> {
    @Override
    protected void updateItem(Match match, boolean empty) {
      super.updateItem(match, empty);
      setText(null);
      if (empty || match == null) {
        setGraphic(null);
        return;
      }
      int nameStart = match.path().lastIndexOf('/') + 1;
      TextFlow name = marked(match, nameStart, match.path().length(), "quick-open-name");
      TextFlow folder = marked(match, 0, Math.max(0, nameStart - 1), "quick-open-folder");
      HBox row = new HBox(8, Icons.forFile(match.file()), name, folder);
      row.setAlignment(Pos.CENTER_LEFT);
      setGraphic(row);
    }

    /** The part {@code from}..{@code to} of the path, matched letters in their own style. */
    private static TextFlow marked(Match match, int from, int to, String style) {
      TextFlow flow = new TextFlow();
      StringBuilder plain = new StringBuilder();
      for (int i = from; i < to; i++) {
        if (match.positions().contains(i)) {
          if (!plain.isEmpty()) {
            flow.getChildren().add(text(plain.toString(), style));
            plain.setLength(0);
          }
          Text hit = text(String.valueOf(match.path().charAt(i)), style);
          hit.getStyleClass().add("quick-open-hit");
          flow.getChildren().add(hit);
        } else {
          plain.append(match.path().charAt(i));
        }
      }
      if (!plain.isEmpty()) {
        flow.getChildren().add(text(plain.toString(), style));
      }
      return flow;
    }

    private static Text text(String content, String style) {
      Text text = new Text(content);
      text.getStyleClass().add(style);
      return text;
    }
  }
}
