package lk.rescuelink;

import android.content.Context;
import java.util.*;
import org.json.*;

/** Durable queue: retries reuse UUID; entries remain bound to server and user. */
final class Outbox {
  static synchronized JSONArray read(Context c) throws Exception {
    String s = Vault.get(c, "outbox");
    return new JSONArray(s.isEmpty() ? "[]" : s);
  }

  static synchronized void enqueue(Context c, Session s, Properties p) throws Exception {
    JSONArray q = read(c);
    if (q.length() >= 25)
      throw new Exception("Outbox full; deliver or remove pending reports first");
    JSONObject o = new JSONObject();
    o.put("account", s.account());
    o.put("status", "NOT DELIVERED");
    JSONObject fields = new JSONObject();
    for (String k : p.stringPropertyNames())
      if (!k.equals("token")) fields.put(k, p.getProperty(k));
    o.put("fields", fields);
    q.put(o);
    Vault.put(c, "outbox", q.toString());
  }

  static synchronized JSONObject next(Context c, Session s) throws Exception {
    JSONArray q = read(c);
    for (int n = 0; n < q.length(); n++) {
      JSONObject o = q.getJSONObject(n);
      if (o.getString("account").equals(s.account())
          && o.getString("status").equals("NOT DELIVERED")) return o;
    }
    return null;
  }

  static Properties fields(JSONObject item, Session s) throws Exception {
    Properties p = new Properties();
    JSONObject f = item.getJSONObject("fields");
    Iterator<String> keys = f.keys();
    while (keys.hasNext()) {
      String k = keys.next();
      p.setProperty(k, f.getString(k));
    }
    p.setProperty("token", s.token);
    return p;
  }

  static synchronized void finish(Context c, String request, String status, String id)
      throws Exception {
    JSONArray q = read(c);
    for (int n = 0; n < q.length(); n++) {
      JSONObject o = q.getJSONObject(n);
      if (o.getJSONObject("fields").getString("request").equals(request)) {
        o.put("status", status);
        o.put("id", id);
        o.getJSONObject("fields").remove("photo");
      }
    }
    Vault.put(c, "outbox", q.toString());
  }

  static synchronized void delete(Context c, String request) throws Exception {
    JSONArray q = read(c);
    for (int n = q.length() - 1; n >= 0; n--)
      if (q.getJSONObject(n).getJSONObject("fields").getString("request").equals(request))
        q.remove(n);
    Vault.put(c, "outbox", q.toString());
  }
}
