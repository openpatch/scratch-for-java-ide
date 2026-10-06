package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.assets.AssetCopier;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;
import org.openpatch.scratch4j.core.assets.BuiltinImage;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.sound.SoundPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Browser for the built-in assets inside the Scratch for Java jar: the
 * Kenney images (search + sheet filter + tiles) and the built-in sounds
 * (search + playback). For the selected asset: insert the name into code,
 * copy it into the project (assets/images, assets/sounds), or copy and open
 * it in the paint/sound editor. Kenney's CC0 credit is shown.
 */
final class AssetLibraryView extends BorderPane {

  private static final int MAX_TILES = 400;

  private final BuiltinAssetIndex assets = BuiltinAssetIndex.get();
  private final ScratchProject project;
  private final BiConsumer<String, String> onInsert; // kind (image/sound), reference
  private final Consumer<Path> onOpenFile;

  private final TextField search = new TextField();
  private final ComboBox<String> sheetFilter = new ComboBox<>();
  private final FlowPane imageGrid = new FlowPane(8, 8);
  private final ToggleGroup imageSelection = new ToggleGroup();
  private final ListView<String> soundList = new ListView<>();
  private final SoundPlayer player = new SoundPlayer();
  private final TabPane tabs = new TabPane();
  private final Label selectedLabel = new Label();
  private final Label status = new Label();
  private final Label countLabel = new Label();

  /** {@code onOpenFile} opens a project file (after "edit a copy") or refreshes after a copy. */
  AssetLibraryView(ScratchProject project, BiConsumer<String, String> onInsert,
      Consumer<Path> onOpenFile) {
    this.project = project;
    this.onInsert = onInsert;
    this.onOpenFile = onOpenFile;
    getStyleClass().add("asset-library");

    sheetFilter.getItems().add(I18n.t("library.allSheets"));
    sheetFilter.getItems().addAll(assets.images().stream()
        .map(BuiltinImage::sheet).distinct().toList());
    sheetFilter.getSelectionModel().selectFirst();
    search.setPromptText(I18n.t("library.search"));
    HBox.setHgrow(search, Priority.ALWAYS);
    search.textProperty().addListener((o, old, value) -> refill());
    sheetFilter.valueProperty().addListener((o, old, value) -> refill());

    imageGrid.setPadding(new Insets(10));
    ScrollPane imageScroll = new ScrollPane(imageGrid);
    imageScroll.setFitToWidth(true);
    imageSelection.selectedToggleProperty().addListener((o, old, toggle) -> updateSelection());

    soundList.setCellFactory(view -> new SoundCell());
    soundList.getSelectionModel().selectedItemProperty().addListener((o, old, v) ->
        updateSelection());
    soundList.setOnMouseClicked(e -> {
      String name = soundList.getSelectionModel().getSelectedItem();
      if (e.getClickCount() == 2 && name != null) {
        onInsert.accept("sound", name);
      }
    });

    Tab imagesTab = new Tab(I18n.t("library.images"), imageScroll);
    imagesTab.setGraphic(Icons.of("fth-image"));
    Tab soundsTab = new Tab(I18n.t("library.sounds"), soundList);
    soundsTab.setGraphic(Icons.of("fth-music"));
    tabs.getTabs().addAll(imagesTab, soundsTab);
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
      sheetFilter.setVisible(tab == imagesTab);
      sheetFilter.setManaged(tab == imagesTab);
      refill();
      updateSelection();
    });

    countLabel.getStyleClass().add("text-muted");
    HBox top = new HBox(8, Icons.of("fth-search"), search, sheetFilter, countLabel);
    top.setAlignment(Pos.CENTER_LEFT);
    top.getStyleClass().add("tool-bar-row");

    setTop(top);
    setCenter(tabs);
    setBottom(actionBar());
    refill();
    updateSelection();
  }

  private Node actionBar() {
    selectedLabel.getStyleClass().add("library-selected");
    Button insert = new Button(I18n.t("library.insert.code"), Icons.of("fth-code"));
    insert.setOnAction(e -> insertSelected());
    Button copy = new Button(I18n.t("library.copy"), Icons.of("fth-download"));
    copy.setOnAction(e -> copySelected(false));
    Button edit = new Button(I18n.t("library.editcopy"), Icons.of("fth-edit-2"));
    edit.getStyleClass().add("accent");
    edit.setOnAction(e -> copySelected(true));
    for (Button b : new Button[] {insert, copy, edit}) {
      b.disableProperty().bind(selectedLabel.textProperty().isEmpty());
      b.setMinWidth(Region.USE_PREF_SIZE);
    }
    Label credits = new Label(I18n.t("library.credits"));
    credits.getStyleClass().add("text-muted");
    credits.setMinWidth(0);
    status.setMinWidth(0);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(8, selectedLabel, insert, copy, edit, status, spacer, credits);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("library-actions");
    return bar;
  }

  private boolean imagesShown() {
    return tabs.getSelectionModel().getSelectedIndex() == 0;
  }

  private String selected() {
    if (imagesShown()) {
      return imageSelection.getSelectedToggle() == null ? null
          : (String) imageSelection.getSelectedToggle().getUserData();
    }
    return soundList.getSelectionModel().getSelectedItem();
  }

  private void updateSelection() {
    String selected = selected();
    selectedLabel.setText(selected == null ? "" : selected);
    selectedLabel.setGraphic(selected == null ? null
        : Icons.of(imagesShown() ? "fth-image" : "fth-music"));
  }

  private void refill() {
    String query = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT);
    if (imagesShown()) {
      refillImages(query);
    } else {
      refillSounds(query);
    }
  }

  private void refillImages(String query) {
    String sheet = sheetFilter.getValue();
    boolean allSheets = sheet == null || sheet.equals(I18n.t("library.allSheets"));
    imageGrid.getChildren().clear();
    int matches = 0;
    for (BuiltinImage image : assets.images()) {
      if (!query.isEmpty() && !image.referenceName().toLowerCase(Locale.ROOT).contains(query)
          || !allSheets && !image.sheet().equals(sheet)) {
        continue;
      }
      matches++;
      if (imageGrid.getChildren().size() < MAX_TILES) {
        imageGrid.getChildren().add(imageTile(image));
      }
    }
    countLabel.setText(matches > MAX_TILES
        ? I18n.t("library.count.more", MAX_TILES, matches) : I18n.t("library.count", matches));
  }

  private Node imageTile(BuiltinImage image) {
    ImageView view = new ImageView(CostumeView.costume(image.referenceName()));
    view.setFitWidth(64);
    view.setFitHeight(64);
    view.setPreserveRatio(true);
    StackPane frame = new StackPane(view);
    frame.setPrefSize(68, 68);
    Label name = new Label(image.name());
    name.getStyleClass().add("tile-label");
    name.setMaxWidth(84);
    VBox box = new VBox(2, frame, name);
    box.setAlignment(Pos.CENTER);
    ToggleButton tile = new ToggleButton(null, box);
    tile.getStyleClass().add("asset-tile");
    tile.setPrefSize(96, 100);
    tile.setToggleGroup(imageSelection);
    tile.setUserData(image.referenceName());
    tile.setTooltip(new Tooltip(image.referenceName() + "  ·  " + image.sheet()
        + "  ·  " + image.width() + "×" + image.height()));
    tile.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        onInsert.accept("image", image.referenceName());
      }
    });
    MenuItem insert = new MenuItem(I18n.t("library.insert.code"), Icons.of("fth-code"));
    insert.setOnAction(e -> onInsert.accept("image", image.referenceName()));
    MenuItem copy = new MenuItem(I18n.t("library.copy"), Icons.of("fth-download"));
    copy.setOnAction(e -> {
      tile.setSelected(true);
      copySelected(false);
    });
    MenuItem edit = new MenuItem(I18n.t("library.editcopy"), Icons.of("fth-edit-2"));
    edit.setOnAction(e -> {
      tile.setSelected(true);
      copySelected(true);
    });
    tile.setContextMenu(new ContextMenu(insert, copy, edit));
    return tile;
  }

  private void refillSounds(String query) {
    var names = assets.sounds().stream()
        .filter(n -> query.isEmpty() || n.toLowerCase(Locale.ROOT).contains(query))
        .toList();
    soundList.getItems().setAll(names);
    countLabel.setText(I18n.t("library.count", names.size()));
  }

  private final class SoundCell extends ListCell<String> {
    @Override
    protected void updateItem(String name, boolean empty) {
      super.updateItem(name, empty);
      if (empty || name == null) {
        setText(null);
        setGraphic(null);
        return;
      }
      Button play = Icons.button("fth-play-circle", I18n.t("soundeditor.play"),
          () -> playSound(name));
      play.getStyleClass().add("play-sound");
      setText(name);
      setGraphic(play);
    }
  }

  private void insertSelected() {
    String selected = selected();
    if (selected != null) {
      onInsert.accept(imagesShown() ? "image" : "sound", selected);
    }
  }

  /** Copies the selected built-in asset into the project; optionally opens the editor. */
  private void copySelected(boolean openEditor) {
    String selected = selected();
    if (selected == null) {
      return;
    }
    try {
      Path copied = imagesShown()
          ? AssetCopier.copyBuiltinImage(assets.image(selected).orElseThrow(), project.root())
          : AssetCopier.copyBuiltinSound(selected, project.root());
      status.setText(I18n.t("library.copied", project.root().relativize(copied)));
      onOpenFile.accept(openEditor ? copied : null);
    } catch (IOException | RuntimeException e) {
      status.setText(e.getMessage());
    }
  }

  private void playSound(String name) {
    try (var in = org.openpatch.scratch.internal.BuiltinSounds.class
        .getResourceAsStream("/" + org.openpatch.scratch.internal.BuiltinSounds.get(name))) {
      var clip = org.openpatch.scratch4j.sound.SoundIO.decode(in);
      boolean started = player.play(clip);
      status.setText(started ? "" : I18n.t("library.noaudio"));
    } catch (IOException | RuntimeException e) {
      status.setText(e.getMessage());
    }
  }
}
