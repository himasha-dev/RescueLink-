package lk.rescuelink;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Keeps native screens clear of Android 15 edge-to-edge system bars and keyboard. */
final class InsetsUi {
  static void apply(View root) {
    int left = root.getPaddingLeft(),
        top = root.getPaddingTop(),
        right = root.getPaddingRight(),
        bottom = root.getPaddingBottom();
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          int t, b, l, r;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            t = bars.top;
            b = Math.max(bars.bottom, keyboard.bottom);
            l = bars.left;
            r = bars.right;
          } else {
            t = insets.getSystemWindowInsetTop();
            b = insets.getSystemWindowInsetBottom();
            l = insets.getSystemWindowInsetLeft();
            r = insets.getSystemWindowInsetRight();
          }
          view.setPadding(left + l, top + t, right + r, bottom + b);
          return insets;
        });
    root.requestApplyInsets();
  }
}
