package org.openpatch.scratch4j.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The layer of each class in the class diagram, from the top: a subclass one
 * below its parent, and a class without a parent one below the classes that
 * hold it in an attribute (a game below its players, a player below its cards).
 * Where the associations go round in a circle, the class that holds the most
 * starts it on top.
 */
final class DiagramLayers {

  private DiagramLayers() {}

  /**
   * @param names        the classes, in a stable order
   * @param parentOf     superclass by class, for the classes whose parent is shown
   * @param associations {from, to} pairs: {@code from} has an attribute of type {@code to}
   */
  static Map<String, Integer> of(List<String> names, Map<String, String> parentOf,
      List<String[]> associations) {
    // edges: parent -> subclass (always), holder -> held class (when it has no parent)
    Map<String, List<String>> below = new LinkedHashMap<>();
    Map<String, Set<String>> held = new HashMap<>();
    for (String n : names) below.put(n, new ArrayList<>());
    for (String n : names) {
      String parent = parentOf.get(n);
      if (parent != null && below.containsKey(parent)) below.get(parent).add(n);
    }
    for (String[] a : associations) {
      if (a[0].equals(a[1]) || !below.containsKey(a[0]) || !below.containsKey(a[1])) continue;
      if (parentOf.get(a[1]) != null && below.containsKey(parentOf.get(a[1]))) continue;
      if (held.computeIfAbsent(a[0], k -> new HashSet<>()).add(a[1])) below.get(a[0]).add(a[1]);
    }
    // a circle of associations is cut where a depth-first walk comes back round
    Map<String, Integer> in = new HashMap<>();
    for (List<String> targets : below.values()) {
      for (String t : targets) in.merge(t, 1, Integer::sum);
    }
    // from the classes without a parent: every other class is reached through its parent,
    // so only an association can lead back to a class still open
    List<String> starts = new ArrayList<>();
    for (String n : names) {
      if (parentOf.get(n) == null || !below.containsKey(parentOf.get(n))) starts.add(n);
    }
    starts.sort(Comparator.comparingInt((String n) -> in.getOrDefault(n, 0) == 0 ? 0 : 1)
        .thenComparingInt(n -> -held.getOrDefault(n, Set.of()).size()));
    Set<String> done = new HashSet<>();
    Set<String> open = new HashSet<>();
    for (String start : starts) cut(start, below, done, open);

    // longest path from the top
    Map<String, Integer> indegree = new HashMap<>();
    for (List<String> targets : below.values()) {
      for (String t : targets) indegree.merge(t, 1, Integer::sum);
    }
    Map<String, Integer> layer = new LinkedHashMap<>();
    Deque<String> ready = new ArrayDeque<>();
    for (String n : names) {
      layer.put(n, 0);
      if (indegree.getOrDefault(n, 0) == 0) ready.add(n);
    }
    while (!ready.isEmpty()) {
      String n = ready.poll();
      for (String t : below.get(n)) {
        layer.put(t, Math.max(layer.get(t), layer.get(n) + 1));
        if (indegree.merge(t, -1, Integer::sum) == 0) ready.add(t);
      }
    }
    return layer;
  }

  /** Depth-first; an association back to a class still open is removed. */
  private static void cut(String n, Map<String, List<String>> below, Set<String> done,
      Set<String> open) {
    if (done.contains(n)) return;
    open.add(n);
    for (String t : new ArrayList<>(below.get(n))) {
      if (open.contains(t)) {
        below.get(n).remove(t);
      } else {
        cut(t, below, done, open);
      }
    }
    open.remove(n);
    done.add(n);
  }
}
