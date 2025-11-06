package com.banjerluke.capacitormeteorwebapp;

import android.util.Log;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "CapacitorMeteorWebApp")
public class CapacitorMeteorWebAppPlugin extends Plugin {
    private static final String TAG = "CapacitorMeteorWebApp";

    private CapacitorMeteorWebApp implementation;

    @Override
    public void load() {
        super.load();
        Log.i(TAG, "🔌 CapacitorMeteorWebAppPlugin.load() called - initializing plugin");
        try {
            implementation = new CapacitorMeteorWebApp(getContext(), getBridge());
            Log.i(TAG, "✅ CapacitorMeteorWebAppPlugin initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize CapacitorMeteorWebApp: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void checkForUpdates(PluginCall call) {
        Log.i(TAG, "checkForUpdates() called from JavaScript");
        if (implementation == null) {
            Log.e(TAG, "Implementation is null, cannot check for updates");
            call.reject("Plugin not initialized");
            return;
        }
        implementation.checkForUpdates(() -> {
            call.resolve();
        });
    }

    @PluginMethod
    public void startupDidComplete(PluginCall call) {
        Log.i(TAG, "startupDidComplete() called from JavaScript");
        if (implementation == null) {
            Log.e(TAG, "Implementation is null, cannot complete startup");
            call.reject("Plugin not initialized");
            return;
        }
        implementation.startupDidComplete(() -> {
            call.resolve();
        });
    }

    @PluginMethod
    public void getCurrentVersion(PluginCall call) {
        Log.d(TAG, "getCurrentVersion() called from JavaScript");
        if (implementation == null) {
            Log.e(TAG, "Implementation is null, cannot get version");
            call.reject("Plugin not initialized");
            return;
        }
        String version = implementation.getCurrentVersion();
        JSObject ret = new JSObject();
        ret.put("version", version);
        call.resolve(ret);
    }

    @PluginMethod
    public void isUpdateAvailable(PluginCall call) {
        Log.d(TAG, "isUpdateAvailable() called from JavaScript");
        if (implementation == null) {
            Log.e(TAG, "Implementation is null, cannot check for updates");
            call.reject("Plugin not initialized");
            return;
        }
        boolean available = implementation.isUpdateAvailable();
        JSObject ret = new JSObject();
        ret.put("available", available);
        call.resolve(ret);
    }

    @PluginMethod
    public void reload(PluginCall call) {
        Log.i(TAG, "reload() called from JavaScript");
        if (implementation == null) {
            Log.e(TAG, "Implementation is null, cannot reload");
            call.reject("Plugin not initialized");
            return;
        }
        implementation.reload(() -> {
            call.resolve();
        });
    }
}
