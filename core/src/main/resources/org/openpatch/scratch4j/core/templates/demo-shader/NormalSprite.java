import org.openpatch.scratch.Sprite;

public class NormalSprite extends Sprite {
  public NormalSprite() {
    // scratch4j:begin setup (managed by the stage designer)
    this.addCostume("cat", "cat.png");
    // scratch4j:end setup
  }

  public void run() {
    this.ifOnEdgeBounce();
    this.move(5);
  }
}
