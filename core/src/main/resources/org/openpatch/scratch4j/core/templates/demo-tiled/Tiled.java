import org.openpatch.scratch.TextureSampling;
import org.openpatch.scratch.Window;
import org.openpatch.scratch.Text;

public class Tiled extends Window {

  public Tiled() {
    super(640, 360, "assets");

    I18n.setup();

    GameState.load();
    I18n.select(GameState.get().locale);

    this.setStage(new World(GameState.get().map, null));

    // begin project settings
    // end project settings
  }

  public static void main(String[] args) {
    // These global settings must be set before starting the game.
    Text.useFont("assets/Retro Gaming.ttf", 11);
    Text.useSmoothing(false);
    Text.SPEAK_BUBBLE_MAX_LIMIT = 200;

    // Start the Game
    // begin project settings
    Window.useTextureSampling(TextureSampling.POINT);
    Window.useFullScreen();
    // end project settings
    new Tiled();
  }
}
