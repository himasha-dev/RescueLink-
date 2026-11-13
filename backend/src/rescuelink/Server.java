package rescuelink;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Dependency-free Java 17 backend. HTTPS is terminated by the demo tunnel. */
public final class Server implements AutoCloseable {
  private final Properties state = new Properties();
  private final Path file;
  private final HttpServer http;
  private final ExecutorService workers = Executors.newFixedThreadPool(48);
  private final Semaphore polls = new Semaphore(24);
  private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private long revision;
  private volatile boolean closed;
  private static final Set<String> ROLES =
      Set.of("CITIZEN", "RESCUE", "HOSPITAL", "DISPATCHER", "ADMIN");
  private static final Set<String> TYPES =
      Set.of("AMBULANCE", "VEHICLE", "RESPONDER", "BED", "EQUIPMENT");

  public Server(Path folder, String host, int port, String adminPassword) throws Exception {
    Files.createDirectories(folder);
    file = folder.resolve("state.properties");
    if (Files.exists(file))
      try (InputStream in = Files.newInputStream(file)) {
        state.load(in);
      }
    revision = Long.parseLong(state.getProperty("revision", "0"));
    if (!state.containsKey("u.admin.role")) {
      passwordValid(adminPassword);
      createUser("admin", adminPassword, "ADMIN", true);
      save();
    }
    http = HttpServer.create(new InetSocketAddress(host, port), 32);
    http.setExecutor(workers);
    http.createContext(
        "/health",
        e -> {
          if (!e.getRequestMethod().equals("GET")) {
            respond(e, 405, props("error", "GET required"));
            return;
          }
          respond(e, 200, props("status", "ready"));
        });
    http.createContext("/api", this::handle);
  }

  public void start() {
    http.start();
  }

  public int port() {
    return http.getAddress().getPort();
  }

  public void close() {
    closed = true;
    synchronized (this) {
      notifyAll();
    }
    http.stop(0);
    workers.shutdownNow();
  }

  static Properties props(String... pairs) {
    Properties p = new Properties();
    for (int i = 0; i < pairs.length; i += 2) p.setProperty(pairs[i], pairs[i + 1]);
    return p;
  }

  private static void need(boolean ok, String message) {
    if (!ok) throw new Bad(400, message);
  }

  private static class Bad extends RuntimeException {
    final int code;

    Bad(int c, String m) {
      super(m);
      code = c;
    }
  }

  private static String required(Properties p, String k) {
    String v = p.getProperty(k, "").trim();
    need(!v.isEmpty(), "Missing " + k);
    return v;
  }

  private static String field(Properties p, String k, int max) {
    String v = required(p, k);
    need(v.length() <= max, "Too long: " + k);
    return v;
  }

  private static String userName(String value) {
    String u = value.toLowerCase(Locale.ROOT).trim();
    need(u.matches("[a-z0-9_]{3,24}"), "Username: 3–24 lowercase letters, digits or underscore");
    return u;
  }

  private static void passwordValid(String value) {
    need(
        value != null && value.length() >= 10 && value.length() <= 128,
        "Password must be 10–128 characters");
  }

  private String secret() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String digest(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hashPassword(String password, String salt) {
    PBEKeySpec spec =
        new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), 210000, 256);
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(spec)
                  .getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  private void createUser(String u, String password, String role, boolean approved) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    String s = Base64.getEncoder().encodeToString(salt);
    state.setProperty("u." + u + ".salt", s);
    state.setProperty("u." + u + ".password", hashPassword(password, s));
    state.setProperty("u." + u + ".role", role);
    state.setProperty("u." + u + ".approved", String.valueOf(approved));
  }

  private void save() throws IOException {
    Path temp = file.resolveSibling("state.tmp");
    try (OutputStream out = Files.newOutputStream(temp)) {
      state.store(out, "RescueLink persistent state; keep private");
    }
    try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      c.force(true);
    }
    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private synchronized String authenticate(Properties p) {
    String token = required(p, "token");
    String base = "s." + digest(token) + ".";
    String u = state.getProperty(base + "user");
    if (u == null
        || Long.parseLong(state.getProperty(base + "expires", "0")) < System.currentTimeMillis())
      throw new Bad(401, "Session expired; log in again");
    if (!Boolean.parseBoolean(state.getProperty("u." + u + ".approved")))
      throw new Bad(403, "Account awaiting administrator approval");
    return u;
  }

  private String role(String u) {
    return state.getProperty("u." + u + ".role");
  }

  private void roleRequired(String u, String... allowed) {
    if (!Arrays.asList(allowed).contains(role(u)))
      throw new Bad(403, "Your role cannot perform this action");
  }

  private Set<String> ids(String prefix, String suffix) {
    Set<String> ids = new TreeSet<>();
    for (String k : state.stringPropertyNames())
      if (k.startsWith(prefix) && k.endsWith(suffix))
        ids.add(k.substring(prefix.length(), k.length() - suffix.length()));
    return ids;
  }

  private boolean visible(String u, String id) {
    if (role(u).equals("ADMIN") || role(u).equals("DISPATCHER")) return true;
    if (u.equals(state.getProperty("i." + id + ".owner"))) return true;
    if (role(u).equals("HOSPITAL") && !"REPORTED".equals(state.getProperty("i." + id + ".status")))
      return true;
    for (String r : allocations(id))
      if (u.equals(state.getProperty("r." + r + ".owner"))) return true;
    return false;
  }

  private void incidentAccess(String u, String id) {
    need(state.containsKey("i." + id + ".owner"), "Unknown incident");
    if (!visible(u, id)) throw new Bad(403, "Incident access denied");
  }

  private Set<String> allocations(String id) {
    return ids("i." + id + ".allocation.", ".quantity");
  }

  private int used(String rid) {
    int n = 0;
    for (String id : ids("i.", ".owner"))
      if (!state.getProperty("i." + id + ".status").equals("RESOLVED"))
        n +=
            Integer.parseInt(
                state.getProperty("i." + id + ".allocation." + rid + ".quantity", "0"));
    return n;
  }

  private void coordinates(Properties p) {
    for (String k : List.of("lat", "lon")) {
      double v;
      try {
        v = Double.parseDouble(required(p, k));
      } catch (NumberFormatException e) {
        throw new Bad(400, "Invalid coordinates");
      }
      need(
          Double.isFinite(v) && Math.abs(v) <= (k.equals("lat") ? 90 : 180), "Invalid coordinates");
    }
  }

  private synchronized Properties snapshot(String u) {
    Properties p =
        props("ok", "true", "revision", String.valueOf(revision), "user", u, "role", role(u));
    for (String id : ids("i.", ".owner"))
      if (visible(u, id)) {
        String base = "i." + id + ".";
        for (String k : state.stringPropertyNames())
          if (k.startsWith(base) && !k.equals(base + "photo"))
            p.setProperty(k, state.getProperty(k));
        if (state.containsKey(base + "photo")) p.setProperty(base + "hasPhoto", "true");
      }
    if (!role(u).equals("CITIZEN"))
      for (String k : state.stringPropertyNames())
        if (k.startsWith("r.")) p.setProperty(k, state.getProperty(k));
    for (String rid : ids("r.", ".name"))
      if (!role(u).equals("CITIZEN"))
        p.setProperty(
            "r." + rid + ".available",
            String.valueOf(Integer.parseInt(state.getProperty("r." + rid + ".total")) - used(rid)));
    if (role(u).equals("ADMIN"))
      for (String name : ids("u.", ".role")) {
        p.setProperty("u." + name + ".role", role(name));
        p.setProperty("u." + name + ".approved", state.getProperty("u." + name + ".approved"));
      }
    return p;
  }

  private String requestHash(Properties p) {
    StringBuilder b = new StringBuilder();
    for (String k : new TreeSet<>(p.stringPropertyNames()))
      if (!k.equals("token"))
        b.append(k.length())
            .append(':')
            .append(k)
            .append(p.getProperty(k).length())
            .append(':')
            .append(p.getProperty(k));
    return digest(b.toString());
  }

}
