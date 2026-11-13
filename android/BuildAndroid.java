import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** Java-only build orchestrator for Eclipse + Android SDK. No Gradle download required. */
public class BuildAndroid {
  private static final boolean windows =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  private static Path sdk, tools, work;

  private static String javaTool(String name) {
    return Path.of(System.getProperty("java.home"), "bin", name + (windows ? ".exe" : ""))
        .toString();
  }

  private static void run(String... args) throws Exception {
    System.out.println("Running " + Path.of(args[0]).getFileName());
    Process p = new ProcessBuilder(args).inheritIO().start();
    if (p.waitFor() != 0) throw new Exception("Tool failed: " + args[0]);
  }

  private static void tool(String name, String... args) throws Exception {
    List<String> a = new ArrayList<>();
    a.add(tools.resolve(name + (windows ? ".exe" : "")).toString());
    a.addAll(Arrays.asList(args));
    run(a.toArray(String[]::new));
  }

  private static void jarTool(String jar, String... args) throws Exception {
    List<String> a =
        new ArrayList<>(
            List.of(javaTool("java"), "-jar", tools.resolve("lib").resolve(jar).toString()));
    a.addAll(Arrays.asList(args));
    run(a.toArray(String[]::new));
  }

  public static void main(String[] args) throws Exception {
    String root = args.length > 0 ? args[0] : System.getenv("ANDROID_HOME");
    if (root == null) throw new Exception("Usage: java BuildAndroid.java PATH_TO_ANDROID_SDK");
    sdk = Path.of(root).toAbsolutePath();
    tools = sdk.resolve("build-tools/35.0.0");
    Path android = sdk.resolve("platforms/android-35/android.jar");
    if (!Files.exists(android) || !Files.exists(tools))
      throw new Exception("Install platforms;android-35 and build-tools;35.0.0 with sdkmanager");
    work = Path.of("build/manual");
    Files.createDirectories(work);
    Path classes = work.resolve("classes"), dex = work.resolve("dex");
    Files.createDirectories(classes);
    Files.createDirectories(dex);
    Path resources = work.resolve("resources.zip");
    tool("aapt2", "compile", "--dir", "app/src/main/res", "-o", resources.toString());
    String manifest =
        Files.readString(Path.of("app/src/main/AndroidManifest.xml"))
            .replace("<manifest ", "<manifest package=\"lk.rescuelink\" ");
    Path mf = work.resolve("AndroidManifest.xml");
    Files.writeString(mf, manifest);
    Path resourceApk = work.resolve("resources.apk");
    tool(
        "aapt2",
        "link",
        "-I",
        android.toString(),
        "--manifest",
        mf.toString(),
        "--min-sdk-version",
        "26",
        "--target-sdk-version",
        "35",
        "--version-code",
        "1",
        "--version-name",
        "1.0-demo",
        "-o",
        resourceApk.toString(),
        resources.toString());
    List<String> options =
        new ArrayList<>(
            List.of("--release", "17", "-classpath", android.toString(), "-d", classes.toString()));
    try (var files = Files.walk(Path.of("app/src/main/java"))) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> options.add(p.toString()));
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new Exception("Use a full JDK 17 or later");
    if (compiler.run(null, null, null, options.toArray(String[]::new)) != 0)
      throw new Exception("Java compilation failed");
    List<String> d8 =
        new ArrayList<>(
            List.of(
                javaTool("java"),
                "-cp",
                tools.resolve("lib/d8.jar").toString(),
                "com.android.tools.r8.D8",
                "--min-api",
                "26",
                "--lib",
                android.toString(),
                "--output",
                dex.toString()));
    try (var files = Files.walk(classes)) {
      files.filter(p -> p.toString().endsWith(".class")).forEach(p -> d8.add(p.toString()));
    }
    run(d8.toArray(String[]::new));
    Path unsigned = work.resolve("unsigned.apk");
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(resourceApk));
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(unsigned))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        out.putNextEntry(new ZipEntry(entry.getName()));
        in.transferTo(out);
        out.closeEntry();
      }
      try (var files = Files.list(dex)) {
        for (Path p : files.filter(f -> f.toString().endsWith(".dex")).toList()) {
          out.putNextEntry(new ZipEntry(p.getFileName().toString()));
          Files.copy(p, out);
          out.closeEntry();
        }
      }
    }
    Path aligned = work.resolve("aligned.apk");
    tool("zipalign", "-f", "-p", "4", unsigned.toString(), aligned.toString());
    Path key = work.resolve("demo.keystore");
    if (!Files.exists(key))
      run(
          javaTool("keytool"),
          "-genkeypair",
          "-keystore",
          key.toString(),
          "-storepass",
          "android",
          "-keypass",
          "android",
          "-alias",
          "demo",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "3650",
          "-dname",
          "CN=RescueLink Demo");
    Path output = Path.of("build/RescueLink-demo.apk");
    jarTool(
        "apksigner.jar",
        "sign",
        "--ks",
        key.toString(),
        "--ks-key-alias",
        "demo",
        "--ks-pass",
        "pass:android",
        "--key-pass",
        "pass:android",
        "--out",
        output.toString(),
        aligned.toString());
    jarTool("apksigner.jar", "verify", "--verbose", output.toString());
    System.out.println("APK: " + output.toAbsolutePath());
  }
}
