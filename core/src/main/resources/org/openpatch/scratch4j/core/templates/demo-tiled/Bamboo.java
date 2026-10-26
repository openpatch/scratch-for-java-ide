public class Bamboo extends Enemy {

  protected double speed = 2.5;

  public Bamboo(double x, double y) {
    super(x, y);

    // begin sprite editor
    this.addAnimation("walk-down", "assets/Bamboo.png", 32, 32, 0, 4, 8, 12); // column 0
    this.addAnimation("walk-up", "assets/Bamboo.png", 32, 32, 1, 5, 9, 13); // column 1
    this.addAnimation("walk-left", "assets/Bamboo.png", 32, 32, 2, 6, 10, 14); // column 2
    this.addAnimation("walk-right", "assets/Bamboo.png", 32, 32, 3, 7, 11, 15); // column 3
    // end sprite editor
  }

  public void run() {
    state = "walk";

    this.changeX(this.speed);

    if (this.speed > 0) {
      dir = "right";
    } else {
      dir = "left";
    }
    this.changeY(0);
    if (this.isTouchingSprite(Wall.class)) {
      state = "idle";
      this.changeX(-this.speed);
      this.speed *= -1;
      this.changeY(0);
    }

    if (this.isTouchingSprite(Fireball.class)) {
      this.remove();
    }

    if (state.equals("idle")) {
      this.setAnimationFrame(0);
    }

    this.playAnimation("walk-" + dir);
  }
}
