package lk.rescuelink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native Java screens. XML/Gradle are declarative build configuration, not app languages. */
public class MainActivity extends Activity {
  private final ExecutorService jobs = Executors.newSingleThreadExecutor();
  private Session session;
  private Properties data = new Properties();
  private LinearLayout root, body;
  private TextView connection;
  private String screen = "login", selectedId = "";
  private Runnable refreshDetail;
  private boolean registered;
  private double selectedLat = 6.032, selectedLon = 80.216;
  private boolean locationSelected;
  private String photo = "";
  private TextView locationLabel, photoLabel;
  private final BroadcastReceiver changes =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          update();
        }
      };

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Notices.channels(this);
    try {
      session = Session.load(this);
    } catch (Exception e) {
      Vault.remove(this, "session");
    }
    if (state != null) {
      selectedLat = state.getDouble("lat", 6.032);
      selectedLon = state.getDouble("lon", 80.216);
      locationSelected = state.getBoolean("selected", false);
    }
    if (session == null) loginScreen();
    else home();
  }

  protected void onSaveInstanceState(Bundle out) {
    out.putDouble("lat", selectedLat);
    out.putDouble("lon", selectedLon);
    out.putBoolean("selected", locationSelected);
    super.onSaveInstanceState(out);
  }

  protected void onResume() {
    super.onResume();
    IntentFilter filter = new IntentFilter(LinkService.UPDATE);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(changes, filter);
    registered = true;
    if (session != null) {
      connect();
      update();
    }
  }

  protected void onPause() {
    if (registered) {
      unregisterReceiver(changes);
      registered = false;
    }
    super.onPause();
  }

  protected void onDestroy() {
    jobs.shutdownNow();
    super.onDestroy();
  }

  private void message(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void error(Exception e) {
    if (!isFinishing())
      new AlertDialog.Builder(this)
          .setTitle("RescueLink")
          .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
          .setPositiveButton("OK", null)
          .show();
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(Color.rgb(16, 46, 70));
    t.setPadding(0, dp(6), 0, dp(6));
    parent.addView(t);
    return t;
  }

  private Button button(LinearLayout parent, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private EditText edit(LinearLayout parent, String hint) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setSingleLine(true);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private Spinner spinner(LinearLayout parent, String... values) {
    Spinner s = new Spinner(this);
    s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
    parent.addView(s);
    return s;
  }

  private String value(EditText e) {
    return e.getText().toString().trim();
  }

  private String choice(Spinner s) {
    return String.valueOf(s.getSelectedItem());
  }

  private void shell(String title) {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(243, 247, 250));
    root.setPadding(dp(18), dp(12), dp(18), dp(8));
    TextView brand = text(root, "RescueLink", 28);
    brand.setTypeface(null, Typeface.BOLD);
    text(root, title, 16);
    connection =
        text(
            root,
            session == null
                ? "Emergency communication & resource coordination"
                : LinkService.status,
            13);
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    InsetsUi.apply(root);
    setContentView(root);
  }

  private interface Task {
    void run() throws Exception;
  }

  private void background(Task task) {
    jobs.execute(
        () -> {
          try {
            task.run();
          } catch (Exception e) {
            runOnUiThread(() -> error(e));
          }
        });
  }

  private String validateUrl(EditText field) throws Exception {
    String s = value(field).trim();
    if (s.contains("://") || s.contains("/") || s.contains(" "))
      throw new Exception("Enter the server IP and TCP port, e.g. 192.168.1.10:9090");
    int colon = s.lastIndexOf(':');
    if (colon <= 0 || colon == s.length() - 1)
      throw new Exception("Enter server IP:port, e.g. 192.168.1.10:9090");
    try {
      int port = Integer.parseInt(s.substring(colon + 1));
      if (port < 1 || port > 65535) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw new Exception("TCP port must be between 1 and 65535, e.g. 192.168.1.10:9090");
    }
    return s;
  }

  private void loginScreen() {
    screen = "login";
    session = null;
    shell("Sign in • sessions remain saved until logout or expiry");
    EditText url = edit(body, "Server IP:TCP port (e.g. 192.168.1.10:9090)");
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    url.setText(getPreferences(0).getString("url", ""));
    EditText username = edit(body, "Username");
    EditText password = edit(body, "Password (at least 10 characters)");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    Spinner role = spinner(body, "CITIZEN", "RESCUE", "HOSPITAL", "DISPATCHER");
    text(
        body,
        "Role selection is for registration. Privileged accounts require administrator approval.",
        14);
    button(
        body,
        "Sign in",
        () -> {
          try {
            String endpoint = validateUrl(url),
                name = value(username),
                pass = password.getText().toString();
            background(
                () -> {
                  Properties r =
                      Api.call(
                          endpoint, Api.fields("op", "login", "username", name, "password", pass));
                  Session s =
                      new Session(
                          endpoint,
                          r.getProperty("token"),
                          r.getProperty("user"),
                          r.getProperty("role"));
                  s.save(this);
                  runOnUiThread(
                      () -> {
                        session = s;
                        getPreferences(0).edit().putString("url", endpoint).apply();
                        home();
                        connect();
                      });
                });
          } catch (Exception e) {
            error(e);
          }
        });
    button(
        body,
        "Register account",
        () -> {
          try {
            String endpoint = validateUrl(url),
                name = value(username),
                pass = password.getText().toString(),
                requested = choice(role);
            background(
                () -> {
                  Properties r =
                      Api.call(
                          endpoint,
                          Api.fields(
                              "op",
                              "register",
                              "username",
                              name,
                              "password",
                              pass,
                              "role",
                              requested));
                  runOnUiThread(() -> message(r.getProperty("message")));
                });
          } catch (Exception e) {
            error(e);
          }
        });
    text(
        body,
        "TCP socket connection required. Use the appropriate emergency contact if help"
            + " is urgent. This coursework TCP build is intended for trusted Wi-Fi/LAN only; raw TCP is not encrypted.",
        14);
  }

  private void connect() {
    if (session == null) return;
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 101);
    try {
      startForegroundService(new Intent(this, LinkService.class));
    } catch (Exception e) {
      error(e);
    }
  }

  private void update() {
    try {
      Session saved = Session.load(this);
      if (saved == null && session != null) {
        session = null;
        loginScreen();
        return;
      }
    } catch (Exception ignored) {
    }
    if (session == null) return;
    Properties live = LinkService.snapshot;
    if (session.user.equals(live.getProperty("user"))) data = live;
    if (connection != null) connection.setText(LinkService.status);
    if (screen.equals("home")) renderHome();
    else if (screen.equals("detail") && refreshDetail != null) refreshDetail.run();
  }

  private Set<String> ids(String prefix, String suffix) {
    Set<String> result = new TreeSet<>();
    for (String k : data.stringPropertyNames())
      if (k.startsWith(prefix) && k.endsWith(suffix))
        result.add(k.substring(prefix.length(), k.length() - suffix.length()));
    return result;
  }

  private void home() {
    screen = "home";
    selectedId = "";
    refreshDetail = null;
    shell(session.user + " • " + session.role);
    renderHome();
  }

  private void renderHome() {
    body.removeAllViews();
    if (session.role.equals("CITIZEN")) {
      button(
          body,
          locationSelected ? "Change SOS location" : "Select SOS location on map",
          () -> map(false, selectedLat, selectedLon));
      text(
          body,
          locationSelected
              ? String.format(Locale.US, "Selected %.5f, %.5f", selectedLat, selectedLon)
              : "Select your position once; SOS uses that position without a description.",
          14);
      Button sos =
          button(
              body,
              "SOS — send emergency notice",
              () -> queueReport("SOS", "CRITICAL", "", true, ""));
      sos.setTextColor(Color.WHITE);
      sos.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(208, 45, 65)));
      button(body, "Create detailed emergency report", this::reportScreen);
      button(body, "Delivery outbox", this::outboxScreen);
    }
    if (!session.role.equals("CITIZEN")) {
      button(body, "Resources & capacities", this::resourcesScreen);
      if (session.role.equals("ADMIN")) button(body, "Approve accounts", this::accountsScreen);
    }
    text(body, "Emergency incidents", 22);
    Set<String> incidentIds = ids("i.", ".owner");
    if (incidentIds.isEmpty())
      text(body, "No incidents received yet. Check the connection status above.", 14);
    List<String> sorted = new ArrayList<>(incidentIds);
    sorted.sort(
        (a, b) ->
            data.getProperty("i." + b + ".created", "")
                .compareTo(data.getProperty("i." + a + ".created", "")));
    for (String id : sorted) {
      String b = "i." + id + ".";
      button(
          body,
          (data.getProperty(b + "sos").equals("true") ? "SOS • " : "")
              + data.getProperty(b + "type")
              + " • "
              + data.getProperty(b + "severity")
              + "\n"
              + data.getProperty(b + "status")
              + " • "
              + id.substring(0, 8),
          () -> detail(id));
    }
    if (!TrackingService.activeId.isEmpty())
      button(
          body,
          "Stop sharing my location",
          () -> stopService(new Intent(this, TrackingService.class)));
    button(body, "Reconnect / enable background alerts", this::connect);
    button(
        body,
        "Disconnect background communication",
        () -> {
          stopService(new Intent(this, TrackingService.class));
          stopService(new Intent(this, LinkService.class));
        });
    button(
        body,
        "Log out",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage(
                    "Background communication and location sharing will stop. Queued reports remain"
                        + " bound to this account and server.")
                .setPositiveButton("Log out", (d, w) -> logout())
                .setNegativeButton("Cancel", null)
                .show());
  }

  private void logout() {
    Session old = session;
    stopService(new Intent(this, TrackingService.class));
    stopService(new Intent(this, LinkService.class));
    Vault.remove(this, "session");
    LinkService.snapshot = new Properties();
    data = new Properties();
    locationSelected = false;
    photo = "";
    loginScreen();
    jobs.execute(
        () -> {
          try {
            Api.call(old.url, old.request("logout"));
          } catch (Exception ignored) {
          }
        });
  }

}
