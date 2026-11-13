package lk.rescuelink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** User-started, visible cross-device emergency messaging connection. */
public class LinkService extends Service {
  static final String UPDATE = "lk.rescuelink.UPDATE";
  static volatile Properties snapshot = new Properties();
  static volatile String status = "Disconnected";
  static volatile boolean running;
  private volatile boolean alive;
  private Session session;
  private ExecutorService threads;
  private final Map<String, String> seen = new HashMap<>();

  public IBinder onBind(Intent i) {
    return null;
  }

  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "STOP".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (alive) return START_NOT_STICKY;
    try {
      session = Session.load(this);
      if (session == null) {
        stopSelf();
        return START_NOT_STICKY;
      }
      Notices.channels(this);
      Intent stop = new Intent(this, LinkService.class).setAction("STOP");
      Notification n =
          new Notification.Builder(this, "link")
              .setSmallIcon(android.R.drawable.ic_dialog_info)
              .setContentTitle("RescueLink connected session")
              .setContentText("Listening for emergency and chat updates")
              .setContentIntent(
                  PendingIntent.getActivity(
                      this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
              .addAction(
                  new Notification.Action.Builder(
                          null,
                          "Disconnect",
                          PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE))
                      .build())
              .build();
      if (Build.VERSION.SDK_INT >= 34)
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
      else startForeground(1, n);
      snapshot = new Properties();
      alive = true;
      running = true;
      threads = Executors.newFixedThreadPool(2);
      threads.execute(this::listen);
      threads.execute(this::deliver);
    } catch (Exception e) {
      publish("Could not start connection: " + e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void publish(String text) {
    status = text;
    sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
  }

  private void listen() {
    long revision = -1;
    int backoff = 1000;
    while (alive) {
      try {
        Properties p = session.request("poll");
        p.setProperty("after", String.valueOf(revision));
        Properties r = Api.call(session.url, p);
        if (!alive) return;
        revision = Long.parseLong(r.getProperty("revision"));
        notifyChanges(r);
        snapshot = r;
        backoff = 1000;
        publish(
            "Online • updates received "
                + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date()));
      } catch (Api.Failure e) {
        if (e.code == 401) {
          expired();
          return;
        }
        publish("Connection unavailable: " + e.getMessage());
        if (!pause(backoff)) return;
        backoff = Math.min(30000, backoff * 2);
      } catch (Exception e) {
        publish("Offline • pending reports are NOT delivered");
        if (!pause(backoff)) return;
        backoff = Math.min(30000, backoff * 2);
      }
    }
  }

  private void notifyChanges(Properties p) {
    for (String key : p.stringPropertyNames())
      if (key.startsWith("i.") && key.endsWith(".status")) {
        String id = key.substring(2, key.length() - 7), base = "i." + id + ".";
        String value = p.getProperty(key) + "|" + p.getProperty(base + "chatCount", "0");
        String previous = seen.put(id, value);
        if (!value.equals(previous)) {
          boolean newIncident = previous == null;
          if (newIncident && p.getProperty(key).equals("RESOLVED")) continue;
          String title =
              p.getProperty(base + "sos", "false").equals("true")
                  ? "SOS emergency"
                  : "Incident update";
          Notices.alert(
              this,
              title,
              p.getProperty(base + "type")
                  + " • "
                  + p.getProperty(key)
                  + " • "
                  + id.substring(0, 8));
        }
      }
  }

  private void deliver() {
    while (alive) {
      try {
        JSONObject item = Outbox.next(this, session);
        if (item != null) {
          Properties p = Outbox.fields(item, session);
          String req = p.getProperty("request");
          try {
            Properties r = Api.call(session.url, p);
            Outbox.finish(this, req, "DELIVERED TO SERVER", r.getProperty("id", ""));
            Notices.alert(
                this,
                "Report delivered",
                "Server receipt received; waiting for dispatcher acknowledgement");
            sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
          } catch (Api.Failure e) {
            if (e.code == 401) {
              expired();
              return;
            }
            if (e.code == 400 || e.code == 403 || e.code == 413) {
              Outbox.finish(this, req, "REJECTED: " + e.getMessage(), "");
              sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
            }
          }
        }
      } catch (Exception ignored) {
      }
      if (!pause(3000)) return;
    }
  }

  private boolean pause(int ms) {
    try {
      Thread.sleep(ms);
      return alive;
    } catch (InterruptedException e) {
      return false;
    }
  }

  private void expired() {
    Vault.remove(this, "session");
    stopService(new Intent(this, TrackingService.class));
    publish("Session expired; log in again. Queued reports remain saved.");
    stopSelf();
  }

  public void onDestroy() {
    alive = false;
    running = false;
    if (threads != null) threads.shutdownNow();
    publish("Disconnected • background alerts stopped");
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
