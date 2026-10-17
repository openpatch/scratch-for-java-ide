package org.openpatch.scratch4j.runner;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Child JVM entry point: the listener owns the structured protocol. */
public final class TeachingChecksMain {
  public static void main(String[] args) {
    var request = LauncherDiscoveryRequestBuilder.request();
    for (String name : args) request.selectors(selectClass(name));
    var failures = new java.util.concurrent.atomic.AtomicInteger();
    var tests = new java.util.concurrent.atomic.AtomicInteger();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(new TestExecutionListener() {
      private synchronized void report(TestIdentifier test, String status, String message, String trace) {
        tests.incrementAndGet();
        if (status.equals("FAILED")) failures.incrementAndGet();
        System.out.println(CheckResult.PREFIX + CheckResult.encode(test.getUniqueId()) + "\t"
            + CheckResult.encode(test.getDisplayName()) + "\t" + status + "\t"
            + CheckResult.encode(message) + "\t" + CheckResult.encode(trace));
        System.out.println(status + ": " + test.getDisplayName());
        if (!trace.isBlank()) System.out.println(trace);
      }
      @Override public void executionSkipped(TestIdentifier test, String reason) {
        if (test.isTest()) report(test, "SKIPPED", reason, "");
      }
      @Override public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        // Container failures include @BeforeAll: without this a broken class looks successful.
        if (!test.isTest() && result.getStatus() != TestExecutionResult.Status.FAILED) return;
        String trace = result.getThrowable().map(t -> {
          var out = new java.io.StringWriter();
          t.printStackTrace(new java.io.PrintWriter(out));
          return out.toString();
        }).orElse("");
        report(test, switch (result.getStatus()) {
          case SUCCESSFUL -> "PASSED";
          case FAILED -> "FAILED";
          case ABORTED -> "SKIPPED";
        }, result.getThrowable().map(t -> t.getMessage() == null ? t.toString() : t.getMessage()).orElse(""), trace);
      }
    });
    try { launcher.execute(request.build()); }
    catch (RuntimeException e) { e.printStackTrace(); System.exit(1); }
    System.exit(tests.get() == 0 || failures.get() > 0 ? 1 : 0);
  }
}
