package dev.nxnative.android;

import android.app.Application;

/** Runs first in every process of the app (the launcher, :game, :pack, :readiness, :selftest): a .nup picked in place opens there as any .nup (PickedNup) */
public final class NxApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PickedNup.install(this);
    }
}
