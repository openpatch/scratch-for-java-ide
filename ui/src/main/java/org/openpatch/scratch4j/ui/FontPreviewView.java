package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A project font ({@code .ttf}/{@code .otf}) at the text sizes the library
 * offers, with the line that makes it usable in code
 * ({@code text.addFont("name", "assets/fonts/x.ttf"); text.setFont("name");} on a
 * {@code Text}).
 */
final class FontPreviewView extends ScrollPane {

  private static final List<Integer> SIZES = List.of(14, 20, 28, 40, 56);

  private final Path file;
  private final VBox samples = new VBox(10);
  private final String fontFamily;

  FontPreviewView(Path projectRoot, Path file, Consumer<String> insert) throws IOException {
    this.file = file;
    String reference = projectRoot.relativize(file).toString().replace('\\', '/');
    String name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "")
        .replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    Font probe;
    try (InputStream in = Files.newInputStream(file)) {
      probe = Font.loadFont(in, 14);
    }
    if (probe == null) {
      throw new IOException(I18n.t("font.unreadable", file.getFileName()));
    }
    fontFamily = probe.getFamily();

    TextField sample = new TextField(I18n.t("font.sample"));
    sample.textProperty().addListener((o, old, text) -> render(text));
    String fontName = name.isEmpty() ? "font" : name;
    // only Text has fonts: addFont registers the file, setFont switches to it
    String line = "text.addFont(\"" + fontName + "\", \"" + reference + "\");\n"
        + "text.setFont(\"" + fontName + "\");";
    Label code = new Label(line);
    code.getStyleClass().add("code-label");
    Button copy = Icons.labeled("fth-clipboard", I18n.t("font.copy"), () -> {
      ClipboardContent content = new ClipboardContent();
      content.putString(line);
      Clipboard.getSystemClipboard().setContent(content);
    });
    Button insertButton = Icons.labeled("fth-corner-down-left", I18n.t("font.insert"),
        () -> insert.accept(line + "\n"));
    code.setWrapText(true);
    Label hint = new Label(I18n.t("font.hint"));
    hint.setWrapText(true);
    hint.getStyleClass().add("text-muted");
    Label title = new Label(fontFamily);
    title.getStyleClass().add("card-title");
    VBox box = new VBox(12, title, new HBox(8, code, copy, insertButton), hint, sample, samples);
    box.setPadding(new Insets(16));
    setContent(box);
    setFitToWidth(true);
    render(sample.getText());
  }

  Path file() {
    return file;
  }

  String fontFamily() {
    return fontFamily;
  }

  private void render(String text) {
    samples.getChildren().clear();
    for (int size : SIZES) {
      Label label = new Label(size + " px  " + text);
      label.setFont(Font.font(fontFamily, size));
      samples.getChildren().add(label);
    }
  }
}
