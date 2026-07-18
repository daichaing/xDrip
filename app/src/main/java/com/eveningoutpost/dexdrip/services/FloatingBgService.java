package com.eveningoutpost.dexdrip.services;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.NotificationChannels;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.xdrip;

import java.util.List;

/**
 * Floating glucose overlay.
 *
 * Shows a small always-on-top window with the current glucose value and the
 * trend arrow. The window is visible whenever the screen is on, refreshes as
 * soon as a new reading arrives (via NewDataObserver) and opens the main app
 * when tapped while the device is unlocked. It can be dragged anywhere on the
 * screen; the value is green while in range (4.0-10.0 mmol/l) and red when
 * out of range so it stands out from the watch face clock.
 */
public class FloatingBgService extends Service {

    private static final String TAG = "FloatingBgService";
    private static final String PREF_ENABLED = "floating_bg_overlay_enabled";
    private static final String PREF_POS_X = "floating_overlay_pos_x";
    private static final String PREF_POS_Y = "floating_overlay_pos_y";
    private static final int FOREGROUND_ID = 8137;
    private static final long REFRESH_INTERVAL_MS = 60_000;

    // in-range thresholds (4.0 - 10.0 mmol/l) expressed in mg/dl
    private static final double RANGE_LOW_MGDL = 4.0 * Constants.MMOLL_TO_MGDL;
    private static final double RANGE_HIGH_MGDL = 10.0 * Constants.MMOLL_TO_MGDL;

    private static final int COLOR_IN_RANGE = 0xFF00E676;    // bright green
    private static final int COLOR_OUT_OF_RANGE = 0xFFFF5252; // bright red
    private static final int COLOR_NO_DATA = 0xFFFFFFFF;      // white

    private static volatile FloatingBgService instance;

    private WindowManager windowManager;
    private View overlayView;
    private TextView valueText;
    private TextView arrowText;
    private boolean overlayShowing = false;

    private int posX;
    private int posY;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshValue();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                hideOverlay();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                showOverlay();
                refreshValue();
            }
        }
    };

    // enable the overlay and start the service (called when the app UI opens)
    public static void enableAndStart(final Context context) {
        Pref.setBoolean(PREF_ENABLED, true);
        start(context);
    }

    // called from NewDataObserver when a new reading arrives
    public static void onNewBgReading() {
        if (!Pref.getBooleanDefaultFalse(PREF_ENABLED)) return;
        final FloatingBgService service = instance;
        if (service != null) {
            service.refreshValue();
        } else {
            // process was restarted, bring the overlay back if it was enabled
            final Context context = xdrip.getAppContext();
            if (context != null) start(context);
        }
    }

    private static void start(final Context context) {
        try {
            final Intent intent = new Intent(context, FloatingBgService.class);
            context.startForegroundService(intent);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not start service: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        posX = Pref.getInt(PREF_POS_X, 6);
        posY = Pref.getInt(PREF_POS_Y, 6);

        final Notification.Builder builder = new Notification.Builder(this,
                NotificationChannels.ONGOING_CHANNEL);
        builder.setContentTitle("xDrip glucose overlay")
                .setSmallIcon(R.drawable.ic_launcher);
        startForeground(FOREGROUND_ID, builder.build());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);

        showOverlay();
        handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showOverlay();
        refreshValue();
        return START_STICKY;
    }

    private boolean isScreenOn() {
        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private void showOverlay() {
        if (overlayShowing || !isScreenOn()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            UserError.Log.w(TAG, "Overlay permission not granted yet");
            return;
        }
        try {
            // inflate with the small-screen scaled context so the overlay matches
            // the enlarged ui on watches
            final Context scaled = xdrip.getDisplayScaledContext(this);
            overlayView = LayoutInflater.from(scaled).inflate(R.layout.floating_bg_overlay, null);
            valueText = overlayView.findViewById(R.id.overlay_bg_value);
            arrowText = overlayView.findViewById(R.id.overlay_bg_arrow);

            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            // x is the distance from the right edge with this gravity
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = clampX(posX);
            params.y = clampY(posY);

            final int touchSlop = ViewConfiguration.get(scaled).getScaledTouchSlop();
            overlayView.setOnTouchListener(new View.OnTouchListener() {
                private int startParamX;
                private int startParamY;
                private float startRawX;
                private float startRawY;
                private boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startParamX = params.x;
                            startParamY = params.y;
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            final float dx = event.getRawX() - startRawX;
                            final float dy = event.getRawY() - startRawY;
                            if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                                dragging = true;
                            }
                            if (dragging) {
                                // END gravity: moving right decreases x
                                params.x = clampX(Math.round(startParamX - dx));
                                params.y = clampY(Math.round(startParamY + dy));
                                posX = params.x;
                                posY = params.y;
                                try {
                                    windowManager.updateViewLayout(overlayView, params);
                                } catch (Exception e) {
                                    // view not attached any more
                                }
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (dragging) {
                                Pref.setInt(PREF_POS_X, posX);
                                Pref.setInt(PREF_POS_Y, posY);
                            } else {
                                onOverlayTapped();
                            }
                            return true;
                    }
                    return false;
                }
            });

            windowManager.addView(overlayView, params);
            overlayShowing = true;
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not add overlay view: " + e);
            overlayView = null;
            overlayShowing = false;
        }
    }

    private void onOverlayTapped() {
        final KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) return; // only react when unlocked
        try {
            final Intent launch = new Intent(this, Home.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not open app from overlay: " + e);
        }
    }

    private int clampX(final int x) {
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final int viewW = overlayView != null ? overlayView.getWidth() : 0;
        final int max = Math.max(0, dm.widthPixels - Math.max(viewW, 40));
        return Math.max(0, Math.min(x, max));
    }

    private int clampY(final int y) {
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final int viewH = overlayView != null ? overlayView.getHeight() : 0;
        final int max = Math.max(0, dm.heightPixels - Math.max(viewH, 40));
        return Math.max(0, Math.min(y, max));
    }

    private void hideOverlay() {
        if (!overlayShowing || overlayView == null) return;
        try {
            windowManager.removeView(overlayView);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not remove overlay view: " + e);
        }
        overlayView = null;
        overlayShowing = false;
    }

    private void refreshValue() {
        if (!overlayShowing || valueText == null) return;
        try {
            final List<BgReading> latest = BgReading.latest(2);
            if (latest == null || latest.isEmpty() || latest.get(0) == null) {
                valueText.setText("--");
                valueText.setTextColor(COLOR_NO_DATA);
                arrowText.setText("");
                return;
            }
            final BgReading bg = latest.get(0);
            valueText.setText(BgGraphBuilder.unitized_string_static(bg.calculated_value));
            final int color = (bg.calculated_value < RANGE_LOW_MGDL
                    || bg.calculated_value > RANGE_HIGH_MGDL)
                    ? COLOR_OUT_OF_RANGE : COLOR_IN_RANGE;
            valueText.setTextColor(color);
            arrowText.setTextColor(color);
            arrowText.setText(bg.displaySlopeArrow());
        } catch (Exception e) {
            UserError.Log.e(TAG, "Could not refresh overlay value: " + e);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(periodicRefresh);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception e) {
            // receiver was not registered
        }
        hideOverlay();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
