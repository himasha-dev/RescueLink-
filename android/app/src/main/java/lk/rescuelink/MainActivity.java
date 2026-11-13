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

  private void map(boolean readOnly, double lat, double lon) {
    Intent i =
        new Intent(this, MapActivity.class)
            .putExtra("lat", lat)
            .putExtra("lon", lon)
            .putExtra("readonly", readOnly)
            .putExtra("id", readOnly ? selectedId : "");
    if (readOnly) startActivity(i);
    else startActivityForResult(i, 201);
  }

  private void reportScreen() {
    screen = "report";
    shell("Create emergency report");
    button(body, "Back to dashboard", this::home);
    Spinner type = spinner(body, "Medical", "Accident", "Fire", "Flood", "Other");
    Spinner severity = spinner(body, "LOW", "MEDIUM", "HIGH", "CRITICAL");
    severity.setSelection(2);
    EditText description = edit(body, "Describe what happened");
    description.setSingleLine(false);
    description.setMinLines(3);
    locationLabel =
        text(
            body,
            locationSelected
                ? "Selected: " + selectedLat + ", " + selectedLon
                : "Location not selected",
            15);
    button(body, "Select location on map", () -> map(false, selectedLat, selectedLon));
    photoLabel = text(body, photo.isEmpty() ? "No photo attached" : "Photo attached", 14);
    button(
        body,
        "Attach photo",
        () -> {
          Intent i =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .setType("image/*")
                  .addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(i, 202);
        });
    button(
        body,
        "Remove photo",
        () -> {
          photo = "";
          photoLabel.setText("No photo attached");
        });
    button(
        body,
        "Send report",
        () -> {
          if (value(description).isEmpty()) {
            message("Add a description, or use SOS on the dashboard");
            return;
          }
          queueReport(choice(type), choice(severity), value(description), false, photo);
        });
  }

  private void queueReport(
      String type, String severity, String description, boolean sos, String attachment) {
    if (!locationSelected) {
      message("Select an emergency location on the map first");
      map(false, selectedLat, selectedLon);
      return;
    }
    Session s = session;
    Properties p =
        Api.fields(
            "op",
            "report",
            "request",
            UUID.randomUUID().toString(),
            "type",
            type,
            "severity",
            severity,
            "description",
            description,
            "sos",
            String.valueOf(sos),
            "lat",
            String.valueOf(selectedLat),
            "lon",
            String.valueOf(selectedLon));
    if (!attachment.isEmpty()) p.setProperty("photo", attachment);
    background(
        () -> {
          Outbox.enqueue(this, s, p);
          runOnUiThread(
              () -> {
                photo = "";
                message("Saved locally — NOT delivered until server receipt arrives");
                home();
                connect();
              });
        });
  }

  private void outboxScreen() {
    screen = "outbox";
    shell("Delivery outbox");
    button(body, "Back", this::home);
    text(
        body,
        "A server receipt is separate from dispatcher acknowledgement. This list includes only this"
            + " account and server.",
        14);
    try {
      JSONArray q = Outbox.read(this);
      int count = 0;
      for (int n = q.length() - 1; n >= 0; n--) {
        JSONObject o = q.getJSONObject(n);
        if (!o.getString("account").equals(session.account())) continue;
        count++;
        JSONObject f = o.getJSONObject("fields");
        String request = f.getString("request");
        text(
            body,
            f.getString("type") + " • " + o.getString("status") + "\n" + o.optString("id", ""),
            15);
        if (!o.optString("id", "").isEmpty()) {
          String id = o.getString("id");
          button(body, "Open incident", () -> detail(id));
        }
        button(
            body,
            o.getString("status").equals("NOT DELIVERED")
                ? "Cancel pending report"
                : "Remove local receipt",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Remove this local entry?")
                    .setMessage(
                        "If sending is already in progress, removal cannot retract a report"
                            + " received by the server.")
                    .setPositiveButton(
                        "Remove",
                        (d, w) -> {
                          try {
                            Outbox.delete(this, request);
                            outboxScreen();
                          } catch (Exception e) {
                            error(e);
                          }
                        })
                    .setNegativeButton("Keep", null)
                    .show());
      }
      if (count == 0) text(body, "No queued reports", 16);
    } catch (Exception e) {
      error(e);
    }
    button(body, "Refresh delivery status", this::outboxScreen);
  }

  private void mutation(String op, String... fields) {
    Session s = session;
    Properties p = s.request(op);
    p.setProperty("request", UUID.randomUUID().toString());
    p.putAll(Api.fields(fields));
    performMutation(s, p);
  }

  private void performMutation(Session s, Properties p) {
    background(
        () -> {
          try {
            Api.call(s.url, p);
            runOnUiThread(() -> message("Server confirmed action"));
          } catch (IOException e) {
            runOnUiThread(
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Action not confirmed")
                        .setMessage(
                            e.getMessage()
                                + "\n"
                                + "Retry safely with the same request ID? Check the live state"
                                + " first.")
                        .setPositiveButton("Retry", (d, w) -> performMutation(s, p))
                        .setNegativeButton("Close", null)
                        .show());
          }
        });
  }

  private void detail(String id) {
    screen = "detail";
    selectedId = id;
    shell("Incident " + id.substring(0, 8));
    button(body, "Back", this::home);
    String base = "i." + id + ".";
    TextView summary = text(body, "Waiting for incident state…", 18);
    TextView allocations = text(body, "", 14);
    TextView chat = text(body, "", 15);
    refreshDetail =
        () -> {
          if (!data.containsKey(base + "owner")) {
            summary.setText("Incident not yet received; wait for the live connection.");
            return;
          }
          summary.setText(
              data.getProperty(base + "type")
                  + " • "
                  + data.getProperty(base + "severity")
                  + "\nStatus: "
                  + data.getProperty(base + "status")
                  + "\nReporter: "
                  + data.getProperty(base + "owner")
                  + "\n"
                  + data.getProperty(base + "description")
                  + "\nLocation: "
                  + data.getProperty(base + "lat")
                  + ", "
                  + data.getProperty(base + "lon")
                  + "\nLatest location update: "
                  + data.getProperty(base + "locationTime", "initial map selection"));
          StringBuilder a = new StringBuilder("Resources\n");
          for (String r : ids(base + "allocation.", ".quantity"))
            a.append(data.getProperty("r." + r + ".name", r.substring(0, 8)))
                .append(" × ")
                .append(data.getProperty(base + "allocation." + r + ".quantity"))
                .append(" • ")
                .append(
                    data.getProperty(base + "allocation." + r + ".accepted").equals("true")
                        ? "accepted"
                        : "awaiting acceptance")
                .append('\n');
          allocations.setText(a.toString());
          StringBuilder c = new StringBuilder("Live chat\n");
          for (String m : ids(base + "chat.", ".text"))
            c.append(data.getProperty(base + "chat." + m + ".user"))
                .append(": ")
                .append(data.getProperty(base + "chat." + m + ".text"))
                .append('\n');
          chat.setText(c.toString());
        };
    refreshDetail.run();
    button(
        body,
        "View latest location on map",
        () -> {
          try {
            map(
                true,
                Double.parseDouble(data.getProperty(base + "lat")),
                Double.parseDouble(data.getProperty(base + "lon")));
          } catch (Exception e) {
            message("Wait for incident data");
          }
        });
    button(
        body,
        "View attached photo",
        () -> {
          Session s = session;
          Properties p = s.request("photo");
          p.setProperty("id", id);
          background(
              () -> {
                Properties r = Api.call(s.url, p);
                String encoded = r.getProperty("photo", "");
                if (encoded.isEmpty()) {
                  runOnUiThread(() -> message("No attached photo"));
                  return;
                }
                byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                runOnUiThread(
                    () -> {
                      ImageView v = new ImageView(this);
                      v.setImageBitmap(image);
                      v.setAdjustViewBounds(true);
                      new AlertDialog.Builder(this)
                          .setTitle("Emergency photo")
                          .setView(v)
                          .setPositiveButton("Close", null)
                          .show();
                    });
              });
        });
    if (session.role.equals("CITIZEN")) {
      button(body, "Start sharing moving location", () -> startTracking(id));
      button(
          body,
          "Stop sharing location",
          () -> stopService(new Intent(this, TrackingService.class)));
    }
    if (session.role.equals("ADMIN") || session.role.equals("DISPATCHER")) {
      button(body, "Acknowledge emergency", () -> mutation("ack", "id", id));
      button(
          body,
          "Resolve incident / release all resources",
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("Resolve incident?")
                  .setMessage("All allocated capacities will be released.")
                  .setPositiveButton("Resolve", (d, w) -> mutation("resolve", "id", id))
                  .setNegativeButton("Cancel", null)
                  .show());
    }
    if (!session.role.equals("CITIZEN")) button(body, "Allocate resources", () -> assignDialog(id));
    if (session.role.equals("RESCUE") || session.role.equals("HOSPITAL")) {
      button(body, "Accept my allocated resources", () -> acceptDialog(id));
      button(body, "Update: en route", () -> mutation("progress", "id", id, "status", "EN_ROUTE"));
      button(body, "Update: on scene", () -> mutation("progress", "id", id, "status", "ON_SCENE"));
    }
    EditText message = edit(body, "Message to incident participants");
    message.setSingleLine(false);
    button(
        body,
        "Send chat message",
        () -> {
          String content = value(message);
          if (content.isEmpty()) return;
          mutation("chat", "id", id, "message", content);
          message.setText("");
        });
  }

  private void startTracking(String id) {
    if (!session.role.equals("CITIZEN")
        || "RESOLVED".equals(data.getProperty("i." + id + ".status"))) {
      message("Tracking requires an active citizen incident");
      return;
    }
    if (!TrackingService.activeId.isEmpty()) {
      message("Stop the existing location sharing session first");
      return;
    }
    selectedId = id;
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          102);
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Share moving location?")
        .setMessage(
            "Your latest device position will replace the map selection and remain visible to"
                + " authorized incident participants. A persistent notification lets you stop"
                + " sharing. Keep device location enabled.")
        .setPositiveButton(
            "Start sharing",
            (d, w) -> {
              try {
                startForegroundService(new Intent(this, TrackingService.class).putExtra("id", id));
              } catch (Exception e) {
                error(e);
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(req, permissions, results);
    if (req == 102) {
      if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
              == PackageManager.PERMISSION_GRANTED
          || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
              == PackageManager.PERMISSION_GRANTED) startTracking(selectedId);
      else message("Permission denied; map-selected location remains available");
    }
    if (req == 101 && results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED)
      message("Notifications disabled; enable them in Android settings to see background alerts");
  }

  private void assignDialog(String id) {
    List<String> options = new ArrayList<>(), keys = new ArrayList<>();
    for (String r : ids("r.", ".name")) {
      if (session.role.equals("ADMIN")
          || session.role.equals("DISPATCHER")
              && !"BED".equals(data.getProperty("r." + r + ".type"))
          || session.user.equals(data.getProperty("r." + r + ".owner"))
              && ((session.role.equals("HOSPITAL")
                      && "BED".equals(data.getProperty("r." + r + ".type")))
                  || (session.role.equals("RESCUE")
                      && "EQUIPMENT".equals(data.getProperty("r." + r + ".type"))))) {
        keys.add(r);
        options.add(
            data.getProperty("r." + r + ".name")
                + " • available "
                + data.getProperty("r." + r + ".available"));
      }
    }
    if (keys.isEmpty()) {
      message("No resources available; register resources first");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Select a resource")
        .setItems(
            options.toArray(new String[0]),
            (d, n) -> {
              EditText qty = new EditText(this);
              qty.setInputType(InputType.TYPE_CLASS_NUMBER);
              qty.setText("1");
              new AlertDialog.Builder(this)
                  .setTitle("Quantity to allocate")
                  .setView(qty)
                  .setPositiveButton(
                      "Allocate",
                      (dialog, w) ->
                          mutation(
                              "assign", "id", id, "resource", keys.get(n), "quantity", value(qty)))
                  .setNegativeButton("Cancel", null)
                  .show();
            })
        .show();
  }

  private void acceptDialog(String id) {
    List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
    for (String r : ids("i." + id + ".allocation.", ".quantity"))
      if (session.user.equals(data.getProperty("r." + r + ".owner"))) {
        keys.add(r);
        labels.add(data.getProperty("r." + r + ".name", r));
      }
    if (keys.isEmpty()) {
      message("No resources assigned to your team/hospital");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Accept assignment")
        .setItems(
            labels.toArray(new String[0]),
            (d, n) -> mutation("accept", "id", id, "resource", keys.get(n)))
        .show();
  }

}
