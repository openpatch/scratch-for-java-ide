package org.openpatch.scratch4j.runner;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.ListeningConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A beginner debugger over JDI: breakpoints on lines of the project's
 * classes, and while paused the current place, the local variables and the
 * fields of {@code this}; continue and step over/into/out. The student program
 * connects to the IDE on start ({@code -agentlib:jdwp=...,server=n}) and waits
 * until the breakpoints are set.
 */
public final class Debugger {

  /** One variable as shown to a student: name, type and a short value. */
  public record Variable(String name, String type, String value) {}

  /** Where the program stopped and what it knows there. */
  public record Pause(String className, String method, String sourceFile, int line,
      List<Variable> locals, List<Variable> fields) {}

  /** Callbacks from the debugger's event thread. */
  public interface Listener {
    void onPaused(Pause pause);

    default void onResumed() {}

    default void onEnded() {}
  }

  private final ListeningConnector connector;
  private final Map<String, Connector.Argument> arguments;
  private final String address;
  /** Breakpoints as "SimpleClassName" -> lines. */
  private final Map<String, Set<Integer>> breakpoints = new ConcurrentHashMap<>();
  private final Listener listener;
  /** Classes with class-prepare requests (so breakpoints follow when they load). */
  private final Set<String> watched = ConcurrentHashMap.newKeySet();
  private volatile VirtualMachine vm;
  private volatile ThreadReference paused;

  /** Starts listening on a free local port; pass {@link #jvmArgument()} to the program. */
  public Debugger(Map<String, Set<Integer>> breakpoints, Listener listener) throws IOException {
    this.listener = listener;
    breakpoints.forEach((name, lines) -> this.breakpoints.put(name, Set.copyOf(lines)));
    this.connector = Bootstrap.virtualMachineManager().listeningConnectors().stream()
        .filter(c -> c.name().equals("com.sun.jdi.SocketListen")).findFirst()
        .orElseThrow(() -> new IOException("No JDI socket connector"));
    this.arguments = connector.defaultArguments();
    arguments.get("localAddress").setValue("127.0.0.1");
    arguments.get("port").setValue("0");
    try {
      this.address = connector.startListening(arguments);
    } catch (IllegalConnectorArgumentsException e) {
      throw new IOException(e);
    }
  }

  /** The JVM flag that makes the program connect here and wait for the breakpoints. */
  public String jvmArgument() {
    return "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=" + address;
  }

  /** Accepts the program's connection (blocking) and starts the event loop. */
  public void attach() throws IOException {
    try {
      vm = connector.accept(arguments);
    } catch (IllegalConnectorArgumentsException e) {
      throw new IOException(e);
    } finally {
      try {
        connector.stopListening(arguments);
      } catch (IllegalConnectorArgumentsException | IOException ignored) {
        // already stopped
      }
    }
    synchronized (this) {
      for (String className : breakpoints.keySet()) {
        watch(className);
      }
    }
    Thread loop = new Thread(this::eventLoop, "scratch4j-debugger");
    loop.setDaemon(true);
    loop.start();
    vm.resume();
  }

  /**
   * Changes the breakpoints of one class while the program runs (a click in the
   * gutter during a debug session): removed lines stop pausing at once, new
   * lines pause from the next time they run.
   */
  public synchronized void setBreakpoints(String className, Set<Integer> lines) {
    if (lines.isEmpty()) {
      breakpoints.remove(className);
    } else {
      breakpoints.put(className, Set.copyOf(lines));
    }
    VirtualMachine machine = vm;
    if (machine == null) {
      return; // attach() sets them
    }
    try {
      EventRequestManager requests = machine.eventRequestManager();
      for (BreakpointRequest request : new ArrayList<>(requests.breakpointRequests())) {
        Location location = request.location();
        if (owner(location.declaringType().name()).equals(className)
            && !lines.contains(location.lineNumber())) {
          requests.deleteEventRequest(request);
        }
      }
      watch(className);
    } catch (com.sun.jdi.VMDisconnectedException ignored) {
      // the program has ended
    }
  }

  /** Breakpoints in the loaded class (and its nested classes) now, and when they load. */
  private void watch(String className) {
    if (watched.add(className)) {
      for (String filter : List.of(className, className + "$*")) {
        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(filter);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
      }
    }
    for (ReferenceType type : vm.allClasses()) {
      if (owner(type.name()).equals(className)) {
        setBreakpoints(type);
      }
    }
  }

  /** The top-level class a (nested) class belongs to: its source file's class. */
  private static String owner(String typeName) {
    int nested = typeName.indexOf('$');
    return nested < 0 ? typeName : typeName.substring(0, nested);
  }

  private synchronized void setBreakpoints(ReferenceType type) {
    Set<Integer> lines = breakpoints.getOrDefault(owner(type.name()), Set.of());
    EventRequestManager requests = vm.eventRequestManager();
    Set<Integer> existing = new java.util.HashSet<>();
    for (BreakpointRequest request : requests.breakpointRequests()) {
      if (request.location().declaringType().equals(type)) {
        existing.add(request.location().lineNumber());
      }
    }
    for (int line : lines) {
      if (existing.contains(line)) {
        continue;
      }
      try {
        for (Location location : type.locationsOfLine(line)) {
          BreakpointRequest request = requests.createBreakpointRequest(location);
          request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
          request.enable();
          break; // one location per line is enough
        }
      } catch (AbsentInformationException ignored) {
        // compiled without line numbers
      }
    }
  }

  private void eventLoop() {
    try {
      while (true) {
        EventSet events = vm.eventQueue().remove();
        boolean resume = true;
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent prepare) {
            setBreakpoints(prepare.referenceType());
          } else if (event instanceof BreakpointEvent || event instanceof StepEvent) {
            if (event instanceof StepEvent) {
              vm.eventRequestManager().deleteEventRequest(event.request());
            }
            ThreadReference thread = ((LocatableEvent) event).thread();
            paused = thread;
            listener.onPaused(describe(thread));
            resume = false;
          } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            listener.onEnded();
            return;
          }
        }
        if (resume) {
          events.resume();
        }
      }
    } catch (InterruptedException | com.sun.jdi.VMDisconnectedException e) {
      listener.onEnded();
    }
  }

  private Pause describe(ThreadReference thread) {
    try {
      StackFrame frame = thread.frame(0);
      Location location = frame.location();
      List<Variable> locals = new ArrayList<>();
      try {
        for (var variable : frame.visibleVariables()) {
          locals.add(new Variable(variable.name(), simple(variable.typeName()),
              show(frame.getValue(variable))));
        }
      } catch (AbsentInformationException e) {
        // no -g: locals unknown
      }
      List<Variable> fields = new ArrayList<>();
      ObjectReference self = frame.thisObject();
      if (self != null) {
        // the class's own fields (a sprite's score, speed, ...), not the library's
        for (Field field : self.referenceType().fields()) {
          if (!field.isStatic() && !field.isSynthetic()) {
            fields.add(new Variable(field.name(), simple(field.typeName()),
                show(self.getValue(field))));
          }
        }
        for (Field field : self.referenceType().fields()) {
          if (field.isStatic() && !field.isSynthetic()) {
            fields.add(new Variable(field.name() + " (static)", simple(field.typeName()),
                show(self.referenceType().getValue(field))));
          }
        }
      }
      String source;
      try {
        source = location.sourceName();
      } catch (AbsentInformationException e) {
        source = location.declaringType().name() + ".java";
      }
      return new Pause(location.declaringType().name(), location.method().name(), source,
          location.lineNumber(), locals, fields);
    } catch (IncompatibleThreadStateException e) {
      return new Pause("?", "?", "?", -1, List.of(), List.of());
    }
  }

  /** Values as a student reads them: 42, "text", Player, [3 items]. */
  static String show(Value value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof StringReference text) {
      return "\"" + text.value() + "\"";
    }
    if (value instanceof PrimitiveValue) {
      return value.toString();
    }
    if (value instanceof ArrayReference array) {
      return simple(array.referenceType().name()) + " [" + array.length() + " items]";
    }
    if (value instanceof ObjectReference object) {
      return simple(object.referenceType().name());
    }
    return value.toString();
  }

  private static String simple(String typeName) {
    return typeName.substring(typeName.lastIndexOf('.') + 1);
  }

  public void resume() {
    paused = null;
    if (vm != null) {
      listener.onResumed();
      vm.resume();
    }
  }

  public void stepOver() {
    step(StepRequest.STEP_OVER);
  }

  public void stepInto() {
    step(StepRequest.STEP_INTO);
  }

  public void stepOut() {
    step(StepRequest.STEP_OUT);
  }

  private void step(int depth) {
    ThreadReference thread = paused;
    if (vm == null || thread == null) {
      return;
    }
    EventRequestManager requests = vm.eventRequestManager();
    requests.deleteEventRequests(List.copyOf(requests.stepRequests()));
    StepRequest step = requests.createStepRequest(thread, StepRequest.STEP_LINE, depth);
    // stay in the student's code: the library and the JDK are stepped over
    for (String excluded : new String[] {"java.*", "javax.*", "jdk.*", "sun.*",
        "org.openpatch.scratch.*", "processing.*", "com.jogamp.*"}) {
      step.addClassExclusionFilter(excluded);
    }
    step.addCountFilter(1);
    step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
    step.enable();
    paused = null;
    listener.onResumed();
    vm.resume();
  }

  /** Whether the program is connected (redefining classes is possible). */
  public boolean isAttached() {
    return vm != null;
  }

  /**
   * Swaps in new versions of loaded classes (class name -> bytecode): their
   * methods run the new code from the next call on. Classes the program has
   * not loaded yet are skipped (they load fresh from disk). Throws
   * {@link UnsupportedOperationException} for changes the JVM cannot swap
   * (new or removed fields and methods, other signatures or superclasses).
   */
  public synchronized List<String> redefine(Map<String, byte[]> classes) {
    VirtualMachine machine = vm;
    if (machine == null) {
      throw new IllegalStateException("The program is not connected");
    }
    if (!machine.canRedefineClasses()) {
      throw new UnsupportedOperationException("This Java cannot swap classes");
    }
    Map<ReferenceType, byte[]> loaded = new java.util.HashMap<>();
    List<String> names = new ArrayList<>();
    classes.forEach((name, bytes) -> {
      for (ReferenceType type : machine.classesByName(name)) {
        loaded.put(type, bytes);
        names.add(name);
      }
    });
    if (!loaded.isEmpty()) {
      machine.redefineClasses(loaded);
    }
    return names;
  }

  /**
   * A changed starting value ({@code int speed = 5;} -> {@code 8}) for the
   * objects that already exist (or the class, for a static attribute). Only
   * values still at the old starting value change: what the game changed
   * while running stays. Returns how many objects (or classes) changed.
   */
  public synchronized int updateStartValue(String className, String fieldName,
      String oldLiteral, String newLiteral, boolean isStatic) {
    VirtualMachine machine = vm;
    if (machine == null) {
      throw new IllegalStateException("The program is not connected");
    }
    int changed = 0;
    for (ReferenceType type : machine.classesByName(className)) {
      if (!(type instanceof com.sun.jdi.ClassType owner)) continue;
      Field field = owner.fieldByName(fieldName);
      if (field == null || field.isFinal()) continue;
      // no old literal: a new attribute, which the JVM started at its default value
      Value oldValue = oldLiteral == null ? defaultValue(machine, field)
          : literal(machine, field, oldLiteral);
      Value newValue = literal(machine, field, newLiteral);
      if (newValue == null || oldValue == null && oldLiteral != null) continue;
      try {
        if (isStatic) {
          if (same(owner.getValue(field), oldValue)) {
            owner.setValue(field, newValue);
            changed++;
          }
          continue;
        }
        // objects of the class and of its subclasses
        for (ReferenceType candidate : machine.allClasses()) {
          if (!(candidate instanceof com.sun.jdi.ClassType ct) || !isSubclass(ct, owner)) {
            continue;
          }
          for (ObjectReference o : ct.instances(0)) {
            if (o.referenceType().equals(ct) && same(o.getValue(field), oldValue)) {
              o.setValue(field, newValue);
              changed++;
            }
          }
        }
      } catch (com.sun.jdi.InvalidTypeException | com.sun.jdi.ClassNotLoadedException e) {
        // the attribute's type changed: that needs a restart anyway
      }
    }
    return changed;
  }

  private static boolean isSubclass(com.sun.jdi.ClassType type, com.sun.jdi.ClassType owner) {
    for (com.sun.jdi.ClassType t = type; t != null; t = t.superclass()) {
      if (t.equals(owner)) return true;
    }
    return false;
  }

  /** 0, 0.0, false, '\0' or null (null for reference types). */
  private static Value defaultValue(VirtualMachine vm, Field field) {
    return switch (field.typeName()) {
      case "int" -> vm.mirrorOf(0);
      case "long" -> vm.mirrorOf(0L);
      case "short" -> vm.mirrorOf((short) 0);
      case "byte" -> vm.mirrorOf((byte) 0);
      case "double" -> vm.mirrorOf(0.0);
      case "float" -> vm.mirrorOf(0.0f);
      case "boolean" -> vm.mirrorOf(false);
      case "char" -> vm.mirrorOf('\0');
      default -> null;
    };
  }

  private static boolean same(Value a, Value b) {
    if (a instanceof StringReference x && b instanceof StringReference y) {
      return x.value().equals(y.value());
    }
    return java.util.Objects.equals(a, b);
  }

  /** A Java literal as a value of the attribute's type ("5", "-2.5f", "'x'", "\"Bob\""). */
  static Value literal(VirtualMachine vm, Field field, String literal) {
    String text = literal.trim().replace("_", "");
    try {
      switch (field.typeName()) {
        case "int" -> {
          return vm.mirrorOf(Integer.decode(text));
        }
        case "long" -> {
          return vm.mirrorOf(Long.decode(text.replaceAll("[lL]$", "")));
        }
        case "short" -> {
          return vm.mirrorOf(Short.decode(text));
        }
        case "byte" -> {
          return vm.mirrorOf(Byte.decode(text));
        }
        case "double" -> {
          return vm.mirrorOf(Double.parseDouble(text));
        }
        case "float" -> {
          return vm.mirrorOf(Float.parseFloat(text));
        }
        case "boolean" -> {
          return text.equals("true") || text.equals("false")
              ? vm.mirrorOf(Boolean.parseBoolean(text)) : null;
        }
        case "char" -> {
          String inner = text.substring(1, text.length() - 1).translateEscapes();
          return inner.length() == 1 ? vm.mirrorOf(inner.charAt(0)) : null;
        }
        case "java.lang.String" -> {
          return text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2
              ? vm.mirrorOf(text.substring(1, text.length() - 1).translateEscapes()) : null;
        }
        default -> {
          return null;
        }
      }
    } catch (RuntimeException e) {
      return null; // e.g. 5 written for a double: "5" parses, "5.0" for an int does not
    }
  }

  /** One object of the running program: its class, values and references. */
  public record ObjectNode(long id, String className, List<Variable> values,
      List<Reference> references) {}

  /** A reference from one object's attribute to another object ({@code role[2]} in lists). */
  public record Reference(String role, long target) {}

  /**
   * The objects of the given classes (and of their subclasses) in the running
   * program: attribute values (numbers, text, booleans; other objects as
   * references when they are of these classes). The program is held for the
   * moment of reading. At most {@code max} objects per class.
   */
  public synchronized List<ObjectNode> objects(java.util.Set<String> classNames, int max) {
    VirtualMachine machine = vm;
    if (machine == null) {
      throw new IllegalStateException("The program is not connected");
    }
    if (!machine.canGetInstanceInfo()) {
      throw new UnsupportedOperationException("This Java cannot list objects");
    }
    boolean wasPaused = paused != null;
    if (!wasPaused) machine.suspend();
    try {
      List<ReferenceType> types = new ArrayList<>();
      for (ReferenceType type : machine.allClasses()) {
        if (classNames.contains(owner(type.name())) && type instanceof com.sun.jdi.ClassType) {
          types.add(type);
        }
      }
      java.util.Map<Long, ObjectReference> found = new java.util.LinkedHashMap<>();
      for (ReferenceType type : types) {
        for (ObjectReference o : type.instances(max)) {
          // instances() includes subclasses' objects only for their own type
          if (o.referenceType().equals(type)) found.put(o.uniqueID(), o);
        }
      }
      List<ObjectNode> out = new ArrayList<>();
      for (ObjectReference o : found.values()) {
        List<Variable> values = new ArrayList<>();
        List<Reference> refs = new ArrayList<>();
        for (Field field : o.referenceType().allFields()) {
          if (field.isStatic() || field.isSynthetic()
              || !classNames.contains(owner(field.declaringType().name()))) {
            continue; // the library's own fields stay out
          }
          Value value = o.getValue(field);
          if (value instanceof ObjectReference ref && found.containsKey(ref.uniqueID())) {
            refs.add(new Reference(field.name(), ref.uniqueID()));
          } else if (value instanceof ObjectReference ref && isList(ref)) {
            int index = 0;
            List<Value> items = listItems(ref);
            for (Value item : items) {
              if (item instanceof ObjectReference r && found.containsKey(r.uniqueID())) {
                refs.add(new Reference(field.name() + "[" + index + "]", r.uniqueID()));
              }
              index++;
            }
            values.add(new Variable(field.name(), simple(field.typeName()),
                "[" + items.size() + "]"));
          } else {
            values.add(new Variable(field.name(), simple(field.typeName()), show(value)));
          }
        }
        out.add(new ObjectNode(o.uniqueID(), simple(o.referenceType().name()), values, refs));
      }
      return out;
    } finally {
      if (!wasPaused) machine.resume();
    }
  }

  private static boolean isList(ObjectReference ref) {
    return ref instanceof ArrayReference || ref.referenceType().name().startsWith("java.util.")
        && ref.referenceType().name().matches("java\\.util\\.(ArrayList|LinkedList|"
            + "concurrent\\.CopyOnWriteArrayList)");
  }

  /** The elements of an array or an ArrayList-like list (read from its fields). */
  private static List<Value> listItems(ObjectReference ref) {
    if (ref instanceof ArrayReference array) {
      return array.getValues();
    }
    try {
      Field data = ref.referenceType().fieldByName("elementData");
      Field size = ref.referenceType().fieldByName("size");
      if (data != null && size != null) {
        ArrayReference array = (ArrayReference) ref.getValue(data);
        int n = ((PrimitiveValue) ref.getValue(size)).intValue();
        return array == null ? List.of() : array.getValues(0, Math.min(n, array.length()));
      }
      Field cow = ref.referenceType().fieldByName("array");
      if (cow != null && ref.getValue(cow) instanceof ArrayReference array) {
        return array.getValues();
      }
    } catch (RuntimeException e) {
      // unknown list layout
    }
    return List.of();
  }

  /** Ends the session (the program keeps running unless it was stopped). */
  public void close() {
    try {
      if (vm != null) {
        vm.dispose();
      }
    } catch (com.sun.jdi.VMDisconnectedException ignored) {
      // already gone
    }
    try {
      connector.stopListening(arguments);
    } catch (IllegalConnectorArgumentsException | IOException ignored) {
      // not listening any more
    }
  }

  /** Breakpoints keyed by simple class name from file -> line numbers. */
  public static Map<String, Set<Integer>> byClass(Map<java.nio.file.Path, Set<Integer>> files) {
    Map<String, Set<Integer>> out = new LinkedHashMap<>();
    files.forEach((file, lines) -> {
      if (!lines.isEmpty()) {
        out.put(file.getFileName().toString().replaceFirst("\\.java$", ""), Set.copyOf(lines));
      }
    });
    return out;
  }
}
