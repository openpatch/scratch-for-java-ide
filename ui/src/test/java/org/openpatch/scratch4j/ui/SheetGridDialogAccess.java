package org.openpatch.scratch4j.ui;

import javafx.scene.image.Image;
import org.openpatch.scratch4j.core.region.SpriteAssets;

import java.nio.file.Path;
import java.util.List;

/** Drives a {@link SheetGridDialog} without showing it modally (layout tests). */
final class SheetGridDialogAccess {

  private final SheetGridDialog grid;
  private final javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog;
  private final int tileWidth;
  private final int tileHeight;
  private final double scale;

  SheetGridDialogAccess(Image sheet, SpriteAssets.Entry entry) throws Exception {
    var ctor = SheetGridDialog.class.getDeclaredConstructor(Image.class, String.class,
        SheetGridDialog.Mode.class, SpriteAssets.Entry.class);
    ctor.setAccessible(true);
    SheetGridDialog.Mode mode = entry == null ? SheetGridDialog.Mode.COSTUMES
        : SheetGridDialog.Mode.ANIMATION;
    grid = ctor.newInstance(sheet, "assets/Skeleton.png", mode, entry);
    dialog = grid.dialog();
    tileWidth = 32;
    tileHeight = 32;
    // the grid's scale: the sheet's larger side fills the view, at most 4x
    scale = Math.max(1, Math.min(4, Math.floor(460 / Math.max(sheet.getWidth(),
        sheet.getHeight()) * 2) / 2));
  }

  void clickCell(int column, int row) {
    grid.click((column + 0.5) * tileWidth * scale, (row + 0.5) * tileHeight * scale);
  }

  List<String> statements() {
    return grid.statements();
  }

  void snapshot(Path file) throws Exception {
    dialog.show();
    dialog.getDialogPane().applyCss();
    dialog.getDialogPane().layout();
    UiSmokeIT.snapshot(dialog.getDialogPane(), file);
    dialog.close();
  }
}
