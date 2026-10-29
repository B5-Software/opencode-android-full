package io.github.opencodeandroid.ui;

import android.os.Bundle;
import android.app.Activity;
import android.app.KeyguardManager;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.test.runner.AndroidJUnitRunner;

/** Keeps fixture navigation separate from the real WebUI's installed service worker and cookies. */
public final class IsolatedWebTestRunner extends AndroidJUnitRunner {
    private boolean wakeTestWindow;
    @Override public void onCreate(Bundle arguments) {
        wakeTestWindow = "true".equals(arguments.getString("wakeTestWindow"));
        if ("true".equals(arguments.getString("isolatedBrowser"))) WebView.setDataDirectorySuffix("ui-validation");
        super.onCreate(arguments);
    }
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (wakeTestWindow) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.setTurnScreenOn(true);
            // Standard OS request only: a secure keyguard still requires the user's credentials.
            activity.getWindow().getDecorView().post(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
                    if (keyguard.isKeyguardLocked()) keyguard.requestDismissKeyguard(activity, null);
                }
            });
        }
    }
}
