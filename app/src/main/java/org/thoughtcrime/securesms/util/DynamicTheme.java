package org.thoughtcrime.securesms.util;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import org.thoughtcrime.securesms.R;

public class DynamicTheme {

  public static final String DARK  = "dark";
  public static final String LIGHT = "light";
  public static final String OLED = "oled"; // JW: added
  public static final String GREEN = "green"; // JW: added
  public static final String BLUE = "blue"; // JW: added

  private int currentTheme;

  public void onCreate(Activity activity) {
    currentTheme = getSelectedTheme(activity);
    activity.setTheme(currentTheme);
  }

  public void onResume(Activity activity) {
    if (currentTheme != getSelectedTheme(activity)) {
      Intent intent = activity.getIntent();
      activity.finish();
      OverridePendingTransition.invoke(activity);
      activity.startActivity(intent);
      OverridePendingTransition.invoke(activity);
    }
  }

  protected int getSelectedTheme(Activity activity) {
    String theme = TextSecurePreferences.getTheme(activity);

    if (theme.equals(DARK)) {
      return R.style.TextSecure_DarkTheme;
    }
    // JW: added
    else if (theme.equals(OLED)) {
      return R.style.TextSecure_DarkThemeOled;
    }
    // JW: added
    else if (theme.equals(GREEN)) {
      return R.style.TextSecure_LightThemeGreen;
    }
    // JW: added
    else if (theme.equals(BLUE)) {
      return R.style.TextSecure_LightThemeBlue;
    }
    return R.style.TextSecure_LightTheme;
  }

  private static final class OverridePendingTransition {
    static void invoke(Activity activity) {
      activity.overridePendingTransition(0, 0);
    }
  }
}
