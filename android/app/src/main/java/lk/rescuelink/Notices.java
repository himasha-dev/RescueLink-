package lk.rescuelink;

import android.app.*;
import android.content.*;

final class Notices {
  static void channels(Context c) {
    NotificationManager m = c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(
        new NotificationChannel(
            "link", "RescueLink connection", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel(
            "tracking", "Location sharing", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel(
            "alerts", "Emergency and chat alerts", NotificationManager.IMPORTANCE_HIGH));
  }

  static Notification make(Context c, String channel, String title, String text) {
    return new Notification.Builder(c, channel)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(
            PendingIntent.getActivity(
                c,
                0,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
        .setAutoCancel(channel.equals("alerts"))
        .build();
  }

  static void alert(Context c, String title, String text) {
    try {
      c.getSystemService(NotificationManager.class)
          .notify(
              2000 + (int) (System.currentTimeMillis() % 100000), make(c, "alerts", title, text));
    } catch (SecurityException ignored) {
    }
  }
}
