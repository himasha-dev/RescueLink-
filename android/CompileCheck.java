import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

public class CompileCheck {
  public static void main(String[] args) throws Exception {
    Files.createDirectories(Path.of("check-out"));
    List<String> a =
        new ArrayList<>(List.of("--release", "17", "-classpath", args[0], "-d", "check-out"));
    try (var f = Files.walk(Path.of("app/src/main/java"))) {
      f.filter(p -> p.toString().endsWith(".java")).forEach(p -> a.add(p.toString()));
    }
    int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, a.toArray(String[]::new));
    if (code != 0) throw new Exception("Compile check failed");
  }
}
