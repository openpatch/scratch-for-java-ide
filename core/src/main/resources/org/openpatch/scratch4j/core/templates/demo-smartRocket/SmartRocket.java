import org.openpatch.scratch.*;

public class SmartRocket extends Window {
  public SmartRocket() {
    super(800, 600, "assets");

    // begin project settings
    this.setStage(new Level());
    // end project settings
  }

  public static void main(String[] args) {
    // begin project settings
    // end project settings
    new SmartRocket();
  }
}
