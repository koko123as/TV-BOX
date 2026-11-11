package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.catvod.Runtime;

/**
 * CFJS shim with the same name as upstream {@code com.fongmi.android.tv.App}, so the upstream loader files
 * copied by scripts/sync-upstream.sh compile unchanged. Only {@link #get()} is used by them. Spider JARs
 * (Guard included) receive this Application through {@code Init.init(Context)}.
 * Like upstream App, {@link #activity()} is the resumed Activity (null while SenPlayer is in front): drive
 * spiders show their login/QR dialogs on it, so logins are done from the 网盘登录 page of this app.
 */
public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static App instance;
    private Handler handler;
    private volatile Activity activity;

    public App() {
        instance = this;
    }

    public static App get() {
        return instance;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        Runtime.set(this);
        Runtime.setActivity(App::activity);
        Runtime.setToast(text -> {
            com.koko.cfjs.BridgeLog.i("[TOAST] " + text);
            post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
        });
        // Spider.dialog / Runtime.dialog (QR and cookie login forms of drive spiders), upstream SpiderDialog.
        Runtime.setDialog(com.koko.cfjs.CfjsDialog::show);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (this.activity == activity) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
