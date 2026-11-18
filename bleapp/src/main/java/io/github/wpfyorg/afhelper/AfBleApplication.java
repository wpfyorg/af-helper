package io.github.wpfyorg.afhelper;

import android.app.Application;

import io.github.wpfyorg.afhelper.log.AfBleLog;

public final class AfBleApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AfBleLog.initialize(this);
    }
}
