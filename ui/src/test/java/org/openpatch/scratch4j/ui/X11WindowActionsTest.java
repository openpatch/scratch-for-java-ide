package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class X11WindowActionsTest {

  @Test
  void requestsOnlyTheMatchingWindowInOurProcess() throws Exception {
    List<List<String>> calls = new ArrayList<>();
    X11WindowActions actions = new X11WindowActions(arguments -> {
      calls.add(List.of(arguments));
      return switch (arguments[1]) {
        case "search" -> "123\n456";
        case "getwindowname" -> arguments[2].equals("123") ? "Dialog" : "Project — Studio";
        default -> "";
      };
    });
    actions.request("Project — Studio", X11WindowActions.Action.MINIMIZE).get(5, TimeUnit.SECONDS);
    assertThat(calls.getFirst()).containsExactly("xdotool", "search", "--onlyvisible", "--pid",
        Long.toString(ProcessHandle.current().pid()));
    assertThat(calls.getLast()).containsExactly("xdotool", "windowminimize", "456");
    assertThat(calls).noneMatch(call -> call.contains("getactivewindow"));
  }

  @Test
  void ambiguousOrMissingWindowsCauseNoAction() throws Exception {
    for (String windows : List.of("", "123\n456")) {
      List<List<String>> calls = new ArrayList<>();
      X11WindowActions actions = new X11WindowActions(arguments -> {
        calls.add(List.of(arguments));
        return arguments[1].equals("search") ? windows : "Studio";
      });
      actions.request("Studio", X11WindowActions.Action.MINIMIZE).get(5, TimeUnit.SECONDS);
      assertThat(calls).noneMatch(call -> call.contains("windowminimize"));
    }
  }

  @Test
  void aMissingHelperIsANoOp() throws Exception {
    X11WindowActions actions = new X11WindowActions(arguments -> { throw new IOException("missing"); });
    actions.request("Studio", X11WindowActions.Action.MINIMIZE).get(5, TimeUnit.SECONDS);
  }

  @Test
  void slowRequestsDoNotBlockTheCallerAndDuplicateClicksAreIgnored() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    X11WindowActions actions = new X11WindowActions(arguments -> {
      entered.countDown();
      release.await(5, TimeUnit.SECONDS);
      return "";
    });
    var pending = actions.request("Studio", X11WindowActions.Action.MINIMIZE);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pending.isDone()).isFalse();
      assertThat(actions.request("Studio", X11WindowActions.Action.MINIMIZE).isDone()).isTrue();
    } finally {
      release.countDown();
    }
    pending.get(5, TimeUnit.SECONDS);
  }

  @Test
  void helperCommandsHaveABoundedWait() throws Exception {
    long started = System.nanoTime();
    assertThat(X11WindowActions.runCommand("sleep", "30")).isEmpty();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5000);
  }
}
