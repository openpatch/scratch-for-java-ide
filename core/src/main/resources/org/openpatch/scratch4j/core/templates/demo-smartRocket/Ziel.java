import org.openpatch.scratch.*;

public class Ziel extends Sprite {

  public Ziel() {
    // scratch4j:begin setup (managed by the stage designer)
    this.addCostume("target", "assets/target.png");
    this.setHitbox(10, 38, 10, 10, 38, 10, 38, 38);
    // scratch4j:end setup
  }
}
