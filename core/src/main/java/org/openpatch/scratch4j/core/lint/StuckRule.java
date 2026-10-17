package org.openpatch.scratch4j.core.lint;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Fixed rule, advanced only by explicit runs. Fires once when a surviving error reaches three. */
public final class StuckRule {
  private final Map<String, Integer> errors = new HashMap<>();
  private String step = "";
  private int stepRuns;
  public record Nudges(Set<String> errors, boolean step) {}
  public Nudges run(Set<String> currentErrors, String currentStep) {
    errors.keySet().retainAll(currentErrors);
    var fired = new java.util.HashSet<String>();
    for (String error : currentErrors) if (errors.merge(error, 1, Integer::sum) == 3) fired.add(error);
    if (currentStep == null || currentStep.isEmpty()) { step = ""; stepRuns = 0; }
    else if (!currentStep.equals(step)) { step = currentStep; stepRuns = 1; }
    else stepRuns++;
    return new Nudges(Set.copyOf(fired), !step.isEmpty() && stepRuns == 3);
  }
  public void clear() { errors.clear(); step = ""; stepRuns = 0; }
}
