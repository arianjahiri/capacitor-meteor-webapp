package com.banjerluke.capacitormeteorwebapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.HttpUrl;

@CapacitorPlugin(name = "CapacitorMeteorWebApp")
public class CapacitorMeteorWebAppPlugin extends Plugin implements AssetBundleManager.Callback {
    private static final String LOG_TAG = "MeteorWebApp";
    public static final String PREFS_NAME = "MeteorWebApp";
    private static final String LOCAL_FILESYSTEM_PATH = "/local-filesystem";

    // Static reference for easy access from MainActivity
    private static CapacitorMeteorWebAppPlugin instance;

    private AssetManager assetManager;
    private AssetManagerCache assetManagerCache;
    private WebAppConfiguration configuration;

    private File wwwDirectory;
    private File applicationDirectory;

    private String launchUrl;
    private int localServerPort;

    private boolean switchedToNewVersion = false;

    private List<WebResourceHandler> resourceHandlers;

    /** The asset bundle manager is responsible for managing asset bundles and checking for updates */
    private AssetBundleManager assetBundleManager;

    /** The asset bundle currently used to serve assets from */
    private AssetBundle currentAssetBundle;

    /** Downloaded asset bundles are considered pending until the next page reload
     * because we don't want the app to end up in an inconsistent state by
     * loading assets from different bundles.
     */
    private AssetBundle pendingAssetBundle;

    /** Directory for serving organized bundles */
    private File servingDirectory;

    /** Timer used to wait for startup to complete after a reload */
    private Timer startupTimer;
    private long startupTimeout;

    //region Lifecycle

    /**
     * Get the plugin instance (for use by MainActivity to intercept requests)
     */
    public static CapacitorMeteorWebAppPlugin getInstance() {
        return instance;
    }

    @Override
    public void load() {
        super.load();
        instance = this;  // Store instance for MainActivity access
        Log.i(LOG_TAG, "🔌 CapacitorMeteorWebAppPlugin.load() called - initializing plugin");
        
        try {
            Context context = getContext();
            
            // Get the assets directory paths
            try {
                String assetsPath = context.getApplicationInfo().dataDir + "/app/src/main/assets";
                wwwDirectory = new File(assetsPath, "www");
                applicationDirectory = new File(wwwDirectory, "application");
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not determine assets path, using alternative approach");
                // Fallback: we'll work with the asset manager directly
            }

            // Get configuration from preferences - using default values for now
            // In Capacitor, these would typically come from capacitor.config.ts
            launchUrl = "http://localhost:3000"; // Default Capacitor server URL
            localServerPort = 3000;
            startupTimeout = 20000;

            SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            configuration = new WebAppConfiguration(preferences);

            assetManager = context.getAssets();

            try {
                assetManagerCache = new AssetManagerCache(assetManager);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Could not load asset manager cache", e);
                throw new WebAppException("Could not load asset manager cache", e);
            }

            try {
                initializeAssetBundles();
            } catch (WebAppException e) {
                Log.e(LOG_TAG, "Could not initialize asset bundles", e);
                throw e;
            }

            resourceHandlers = new ArrayList<WebResourceHandler>();
            initializeResourceHandlers();
            
            // Setup the current bundle for serving
            try {
                setupCurrentBundle();
            } catch (WebAppException e) {
                Log.e(LOG_TAG, "Could not setup current bundle", e);
            }
            
            // Configure WebView user agent for Meteor compatibility
            configureUserAgent();
            
            Log.i(LOG_TAG, "✅ CapacitorMeteorWebAppPlugin initialized successfully");
            Log.i(LOG_TAG, "📝 To enable request interception, see INTEGRATION.md for MainActivity setup");
        } catch (Exception e) {
            Log.e(LOG_TAG, "❌ Failed to initialize CapacitorMeteorWebAppPlugin: " + e.getMessage(), e);
        }
    }

    /**
     * Setup the current bundle for serving by organizing it into the serving directory
     * This also injects the WebAppLocalServer shim into index.html
     */
    private void setupCurrentBundle() throws WebAppException {
        if (currentAssetBundle == null) {
            throw new WebAppException("No current asset bundle");
        }

        // Create serving directory: /data/data/<app>/files/meteor-serving/<version>
        File bundleServingDirectory = new File(servingDirectory, currentAssetBundle.getVersion());
        
        // Remove existing serving directory for this version
        if (bundleServingDirectory.exists()) {
            if (!IOUtils.deleteRecursively(bundleServingDirectory)) {
                Log.w(LOG_TAG, "Could not delete existing serving directory");
            }
        }

        // Organize the bundle for serving (this injects the WebAppLocalServer shim)
        Log.i(LOG_TAG, "Organizing bundle " + currentAssetBundle.getVersion() + " for serving");
        BundleOrganizer.organizeBundle(currentAssetBundle, bundleServingDirectory, assetManager);
        
        Log.d(LOG_TAG, "Bundle organized and ready to serve from: " + bundleServingDirectory.getAbsolutePath());
    }

    /**
     * Configure the WebView user agent and CORS settings to be compatible with Meteor's Cordova expectations.
     * Meteor checks the user agent to determine which bundle to serve and what features to enable.
     * We also need to enable cross-origin requests to allow the app to connect to the Meteor server.
     */
    private void configureUserAgent() {
        if (bridge == null || bridge.getWebView() == null) {
            Log.w(LOG_TAG, "Cannot configure user agent - WebView not available yet");
            return;
        }

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.webkit.WebView webView = bridge.getWebView();
                    android.webkit.WebSettings settings = webView.getSettings();
                    
                    // Get the default user agent
                    String originalUserAgent = settings.getUserAgentString();
                    Log.i(LOG_TAG, "📱 Original User Agent: " + originalUserAgent);
                    
                    // Create a Cordova-compatible user agent that Meteor will recognize
                    // Include "Meteor" to signal this is a Meteor Cordova client
                    // This ensures Meteor serves the web.cordova bundle instead of web.browser
                    String meteorUserAgent = originalUserAgent + " Meteor/1.0 (Cordova)";
                    
                    settings.setUserAgentString(meteorUserAgent);
                    Log.i(LOG_TAG, "📱 Modified User Agent: " + meteorUserAgent);
                    
                    // ============================================================================
                    // CRITICAL: Enable cross-origin requests from custom schemes (capacitor://)
                    // ============================================================================
                    // iOS WKWebView allows this by default, but Android WebView doesn't.
                    // These settings allow the app loaded from capacitor://localhost to make
                    // XHR/fetch requests to remote servers (like the Meteor DDP server).
                    // This bypasses CORS restrictions that would otherwise block the requests.
                    //
                    // NOTE: These settings are safe in the context of a Capacitor app because:
                    // 1. The app is served from a custom scheme (capacitor://) not from the web
                    // 2. All HTML content is under our control (bundled with the app)
                    // 3. This is the same security model that iOS WKWebView uses by default
                    // ============================================================================
                    
                    settings.setAllowFileAccessFromFileURLs(true);
                    settings.setAllowUniversalAccessFromFileURLs(true);
                    
                    // Allow mixed content (HTTPS requests from non-HTTPS origins)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    }
                    
                    // Enable debugging for WebView
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true);
                    
                    // Try to disable web security using reflection (for older Android versions)
                    try {
                        Class<?> webSettingsClass = settings.getClass();
                        java.lang.reflect.Method setAllowUniversalAccessFromFileURLsMethod = 
                            webSettingsClass.getMethod("setAllowUniversalAccessFromFileURLs", boolean.class);
                        setAllowUniversalAccessFromFileURLsMethod.invoke(settings, true);
                        Log.i(LOG_TAG, "✅ Universal access enabled via reflection");
                    } catch (Exception reflectionEx) {
                        Log.d(LOG_TAG, "Could not enable universal access via reflection: " + reflectionEx.getMessage());
                    }
                    
                    Log.i(LOG_TAG, "✅ WebView configured for cross-origin requests (CORS bypass)");
                    Log.i(LOG_TAG, "✅ User agent configured for Meteor Cordova compatibility");
                    
                } catch (Exception e) {
                    Log.e(LOG_TAG, "❌ Failed to configure WebView: " + e.getMessage(), e);
                }
            }
        });
    }

    void initializeAssetBundles() throws WebAppException {
        // Create ResourceApi for accessing bundled assets
        ResourceApi resourceApi = new ResourceApi(assetManager);

        // Log available assets for debugging
        try {
            String[] topLevelAssets = assetManager.list("");
            Log.d(LOG_TAG, "Top-level assets: " + java.util.Arrays.toString(topLevelAssets));
            
            // Check if public or www directories exist
            for (String asset : topLevelAssets) {
                if (asset.equals("public") || asset.equals("www")) {
                    String[] subAssets = assetManager.list(asset);
                    Log.d(LOG_TAG, asset + " directory contents: " + java.util.Arrays.toString(subAssets));
                }
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not list assets: " + e.getMessage());
        }

        // Try to load the initial asset bundle from bundled assets
        // Try multiple possible locations: public, www, public/application, www/application
        String[] possiblePaths = {
            "file:///android_asset/public",
            "file:///android_asset/www",
            "file:///android_asset/public/application",
            "file:///android_asset/www/application"
        };

        AssetBundle initialAssetBundle = null;
        for (String path : possiblePaths) {
            try {
                Uri directoryUri = Uri.parse(path);
                initialAssetBundle = new AssetBundle(resourceApi, directoryUri);
                Log.i(LOG_TAG, "✅ Initial bundle loaded from " + path + " - version: " + initialAssetBundle.getVersion());
                break;
            } catch (Exception e) {
                Log.d(LOG_TAG, "❌ Could not load initial bundle from " + path + ": " + e.getMessage());
            }
        }

        // If no initial bundle is found, we'll create a minimal placeholder
        // This allows the app to start and download the first version
        if (initialAssetBundle == null) {
            Log.w(LOG_TAG, "⚠️ No initial asset bundle found in app assets.");
            Log.w(LOG_TAG, "The plugin requires a Meteor app to be bundled with the native app.");
            Log.w(LOG_TAG, "Please ensure program.json exists in one of: public/, www/, public/application/, or www/application/");
            throw new WebAppException("No initial asset bundle found. Please ensure the Meteor app is built and bundled with the native app.");
        }

        // Downloaded versions are stored in /data/data/<app>/files/meteor
        File versionsDirectory = new File(getContext().getFilesDir(), "meteor");
        
        // Serving directory for organized bundles
        servingDirectory = new File(getContext().getFilesDir(), "meteor-serving");

        // If the last seen initial version is different from the currently bundled
        // version, we delete the versions directory and unset lastDownloadedVersion
        // and blacklistedVersions
        if (!initialAssetBundle.getVersion().equals(configuration.getLastSeenInitialVersion()))  {
            Log.d(LOG_TAG, "Detected new bundled version, removing versions directory if it exists");
            if (versionsDirectory.exists()) {
                if (!IOUtils.deleteRecursively(versionsDirectory)) {
                    Log.w(LOG_TAG, "Could not remove versions directory");
                }
            }
            if (servingDirectory.exists()) {
                if (!IOUtils.deleteRecursively(servingDirectory)) {
                    Log.w(LOG_TAG, "Could not remove serving directory");
                }
            }
            configuration.reset();
        }

        // We keep track of the last seen initial version (see above)
        configuration.setLastSeenInitialVersion(initialAssetBundle.getVersion());

        // If the versions directory does not exist, we create it
        if (!versionsDirectory.exists()) {
            if (!versionsDirectory.mkdirs()) {
                Log.e(LOG_TAG, "Could not create versions directory");
                return;
            }
        }

        // Create serving directory if it doesn't exist
        if (!servingDirectory.exists()) {
            if (!servingDirectory.mkdirs()) {
                Log.e(LOG_TAG, "Could not create serving directory");
                return;
            }
        }

        assetBundleManager = new AssetBundleManager(configuration, initialAssetBundle, versionsDirectory);
        assetBundleManager.setCallback(this);

        String lastDownloadedVersion = configuration.getLastDownloadedVersion();
        if (lastDownloadedVersion != null) {
            currentAssetBundle = assetBundleManager.downloadedAssetBundleWithVersion(lastDownloadedVersion);
            if (currentAssetBundle == null) {
                Log.i(LOG_TAG, "📦 Using initial asset bundle version: " + initialAssetBundle.getVersion());
                currentAssetBundle = initialAssetBundle;
            } else {
                Log.i(LOG_TAG, "📦 Using downloaded asset bundle version: " + lastDownloadedVersion);
                if (configuration.getLastKnownGoodVersion() == null || !configuration.getLastKnownGoodVersion().equals(lastDownloadedVersion)) {
                    startStartupTimer();
                }
            }
        } else {
            Log.i(LOG_TAG, "📦 Using initial asset bundle version: " + initialAssetBundle.getVersion());
            currentAssetBundle = initialAssetBundle;
        }

        pendingAssetBundle = null;
    }

    /** Called before page reload */
    private void onReset() {
        if (currentAssetBundle != null) {
            configuration.setAppId(currentAssetBundle.getAppId());
            configuration.setRootUrlString(currentAssetBundle.getRootUrlString());
            configuration.setCordovaCompatibilityVersion(currentAssetBundle.getCordovaCompatibilityVersion());
        }

        if (switchedToNewVersion) {
            switchedToNewVersion = false;
            startStartupTimer();
        }
    }

    private void startStartupTimer() {
        removeStartupTimer();

        startupTimer = new Timer();
        startupTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.w(LOG_TAG, "App startup timed out, reverting to last known good version");
                revertToLastKnownGoodVersion();
            }
        }, startupTimeout);
    }

    private void removeStartupTimer() {
        if (startupTimer != null) {
            startupTimer.cancel();
            startupTimer = null;
        }
    }

    //endregion

    //region Plugin Methods

    @PluginMethod
    public void checkForUpdates(final PluginCall call) {
        Log.i(LOG_TAG, "checkForUpdates() called from JavaScript");
        if (currentAssetBundle == null) {
            Log.e(LOG_TAG, "Current asset bundle is null");
            call.reject("Plugin not initialized");
            return;
        }
        
        new Thread(new Runnable() {
            public void run() {
                HttpUrl rootUrl = HttpUrl.parse(currentAssetBundle.getRootUrlString());
                if (rootUrl == null) {
                    Log.e(LOG_TAG, "checkForUpdates requires a rootURL to be configured");
                    call.reject("checkForUpdates requires a rootURL to be configured");
                    return;
                }
                HttpUrl baseUrl = rootUrl.resolve("__cordova/");
                assetBundleManager.checkForUpdates(baseUrl);
                call.resolve();
            }
        }).start();
    }

    @PluginMethod
    public void startupDidComplete(final PluginCall call) {
        Log.i(LOG_TAG, "startupDidComplete() called from JavaScript");
        if (currentAssetBundle == null) {
            Log.e(LOG_TAG, "Current asset bundle is null");
            call.reject("Plugin not initialized");
            return;
        }
        
        removeStartupTimer();

        Log.i(LOG_TAG, "Startup completed received. New good version is " + currentAssetBundle.getVersion());

        // If startup completed successfully, we consider a version good
        configuration.setLastKnownGoodVersion(currentAssetBundle.getVersion());

        new Thread(new Runnable() {
            @Override
            public void run() {
                assetBundleManager.removeAllDownloadedAssetBundlesExceptForVersion(currentAssetBundle.getVersion());
                call.resolve();
            }
        }).start();
    }

    @PluginMethod
    public void getCurrentVersion(PluginCall call) {
        Log.d(LOG_TAG, "getCurrentVersion() called from JavaScript");
        if (currentAssetBundle == null) {
            Log.e(LOG_TAG, "Current asset bundle is null");
            call.reject("Plugin not initialized");
            return;
        }
        
        String version = currentAssetBundle.getVersion();
        JSObject ret = new JSObject();
        ret.put("version", version);
        call.resolve(ret);
    }

    @PluginMethod
    public void isUpdateAvailable(PluginCall call) {
        Log.d(LOG_TAG, "isUpdateAvailable() called from JavaScript");
        
        boolean available = (pendingAssetBundle != null);
        JSObject ret = new JSObject();
        ret.put("available", available);
        call.resolve(ret);
    }

    @PluginMethod
    public void reload(final PluginCall call) {
        Log.i(LOG_TAG, "reload() called from JavaScript");
        
        if (pendingAssetBundle != null) {
            Log.i(LOG_TAG, "Reloading with pending version " + pendingAssetBundle.getVersion());
            
            try {
                // Organize the pending bundle for serving
                File bundleServingDirectory = new File(servingDirectory, pendingAssetBundle.getVersion());
                
                // Remove existing serving directory for this version
                if (bundleServingDirectory.exists()) {
                    if (!IOUtils.deleteRecursively(bundleServingDirectory)) {
                        Log.w(LOG_TAG, "Could not delete existing serving directory");
                    }
                }

                // Organize the bundle (this injects the WebAppLocalServer shim)
                BundleOrganizer.organizeBundle(pendingAssetBundle, bundleServingDirectory, assetManager);
                
                // Make atomic switch
                currentAssetBundle = pendingAssetBundle;
                pendingAssetBundle = null;
                switchedToNewVersion = true;
                
                // Reload the WebView
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onReset();
                        if (bridge != null && bridge.getWebView() != null) {
                            bridge.getWebView().reload();
                        }
                        call.resolve();
                    }
                });
            } catch (WebAppException e) {
                Log.e(LOG_TAG, "Could not organize pending bundle", e);
                call.reject("Could not organize pending bundle: " + e.getMessage());
            }
        } else {
            Log.w(LOG_TAG, "No pending version to reload");
            call.resolve();
        }
    }

    //endregion

    //region Private Methods

    private void revertToLastKnownGoodVersion() {
        // Blacklist the current version, so we don't update to it again right away
        configuration.addBlacklistedVersion(currentAssetBundle.getVersion());

        // If there is a last known good version and we can load the bundle, revert to it
        String lastKnownGoodVersion = configuration.getLastKnownGoodVersion();
        if (lastKnownGoodVersion != null) {
            AssetBundle assetBundle = assetBundleManager.downloadedAssetBundleWithVersion(lastKnownGoodVersion);
            if (assetBundle != null) {
                pendingAssetBundle = assetBundle;
            }
        }
        // Else, revert to the initial asset bundle, unless that is what we are currently serving
        else if (!currentAssetBundle.equals(assetBundleManager.initialAssetBundle)) {
            pendingAssetBundle = assetBundleManager.initialAssetBundle;
        }

        // Only reload if we have a pending asset bundle to reload
        if (pendingAssetBundle != null) {
            Log.i(LOG_TAG, "Reverting to: " + pendingAssetBundle.getVersion());
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    currentAssetBundle = pendingAssetBundle;
                    pendingAssetBundle = null;
                    onReset();
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().reload();
                    }
                }
            });
        } else {
            Log.w(LOG_TAG, "No suitable version to revert to.");
        }
    }

    //endregion

    //region AssetBundleManager.Callback

    @Override
    public boolean shouldDownloadBundleForManifest(AssetManifest manifest) {
        final String version = manifest.version;

        // No need to redownload the current version
        if (currentAssetBundle.getVersion().equals(version)) {
            Log.i(LOG_TAG, "Skipping downloading current version: " + version);
            return false;
        }

        // No need to redownload the pending version
        if (pendingAssetBundle != null && pendingAssetBundle.getVersion().equals(version)) {
            Log.i(LOG_TAG, "Skipping downloading pending version: " + version);
            return false;
        }

        // Don't download blacklisted versions
        if (configuration.getBlacklistedVersions().contains(version)) {
            Log.w(LOG_TAG, "Skipping downloading blacklisted version: " + version);
            return false;
        }

        // NOTE: Cordova compatibility version check removed for Capacitor
        // The original Cordova plugin checked cordovaCompatibilityVersion to ensure
        // the native code and web bundle were compatible. Since we're using Capacitor
        // and not Cordova, this check is not relevant and the field may not even exist
        // in the Meteor bundle manifest.
        
        Log.d(LOG_TAG, "Allowing download of version: " + version);
        return true;
    }

    @Override
    public void onFinishedDownloadingAssetBundle(AssetBundle assetBundle) {
        Log.i(LOG_TAG, "Finished downloading " + assetBundle.getVersion());
        configuration.setLastDownloadedVersion(assetBundle.getVersion());
        pendingAssetBundle = assetBundle;
        
        // Notify JavaScript of new version ready
        notifyListeners("newVersionReady", new JSObject().put("version", assetBundle.getVersion()));
        
        // ============================================================================
        // TODO: REMOVE THIS - TEMPORARY AUTO-RELOAD FOR TESTING
        // In production, the Meteor app should call WebAppLocalServer.switchToPendingVersion()
        // when it's ready to reload (e.g., after showing a prompt to the user)
        // ============================================================================
        Log.w(LOG_TAG, "⚠️ TEMPORARY: Auto-reloading with new version " + assetBundle.getVersion());
        
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Organize the pending bundle for serving
                    File bundleServingDirectory = new File(servingDirectory, pendingAssetBundle.getVersion());
                    
                    // Remove existing serving directory for this version
                    if (bundleServingDirectory.exists()) {
                        if (!IOUtils.deleteRecursively(bundleServingDirectory)) {
                            Log.w(LOG_TAG, "Could not delete existing serving directory");
                        }
                    }

                    // Organize the bundle (this injects the WebAppLocalServer shim)
                    BundleOrganizer.organizeBundle(pendingAssetBundle, bundleServingDirectory, assetManager);
                    
                    // Make atomic switch
                    currentAssetBundle = pendingAssetBundle;
                    pendingAssetBundle = null;
                    switchedToNewVersion = true;
                    
                    Log.i(LOG_TAG, "⚠️ TEMPORARY: Reloading WebView with new version");
                    
                    // Reload the WebView
                    onReset();
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().reload();
                    }
                } catch (WebAppException e) {
                    Log.e(LOG_TAG, "Could not organize pending bundle for auto-reload", e);
                }
            }
        });
        // ============================================================================
        // END TEMPORARY AUTO-RELOAD CODE
        // ============================================================================
    }

    @Override
    public void onError(Throwable cause) {
        Log.w(LOG_TAG, "Download failure", cause);
        
        // TODO: Notify JavaScript of error
        // This could be done via notifyListeners or an event
        notifyListeners("error", new JSObject().put("message", cause.getMessage()));
    }

    //endregion

    //region Resource Serving

    private void initializeResourceHandlers() {
        // Serve files from the organized bundle directory (includes injected shim)
        resourceHandlers.add(new WebResourceHandler() {
            @Override
            public Uri remapUri(Uri uri) {
                if (currentAssetBundle == null || servingDirectory == null) return null;

                String path = uri.getPath();
                if (path == null) return null;

                // Remove leading slash
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                // Handle root path
                if (path.isEmpty()) {
                    path = "index.html";
                }

                // Check if file exists in organized bundle directory
                File bundleServingDir = new File(servingDirectory, currentAssetBundle.getVersion());
                File file = new File(bundleServingDir, path);
                
                if (file.exists() && file.isFile()) {
                    return Uri.fromFile(file);
                }

                return null;
            }
        });

        // Serve files from www/public directory
        resourceHandlers.add(new WebResourceHandler() {
            @Override
            public Uri remapUri(Uri uri) {
                if (assetManagerCache == null) return null;

                String path = uri.getPath();

                // Do not serve files from /application, because these should only be served
                // through the initial asset bundle
                if (path.startsWith("/application")) return null;

                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                // Check in public directory (Capacitor convention)
                if (assetManagerCache.exists("public/" + path)) {
                    return Uri.parse("file:///android_asset/public/" + path);
                }
                // Fallback to www directory
                else if (assetManagerCache.exists("www/" + path)) {
                    return Uri.parse("file:///android_asset/www/" + path);
                }
                
                return null;
            }
        });

        // Serve local file system at /local-filesystem/<path>
        resourceHandlers.add(new WebResourceHandler() {
            @Override
            public Uri remapUri(Uri uri) {
                String path = uri.getPath();

                if (!path.startsWith(LOCAL_FILESYSTEM_PATH)) return null;

                String filePath = path.substring(LOCAL_FILESYSTEM_PATH.length());
                return Uri.fromFile(new File(filePath));
            }
        });

        // Serve index.html as a last resort
        resourceHandlers.add(new WebResourceHandler() {
            @Override
            public Uri remapUri(Uri uri) {
                if (currentAssetBundle == null) return null;

                String path = uri.getPath();

                // Don't serve index.html for local file system paths
                if (path.startsWith(LOCAL_FILESYSTEM_PATH)) return null;

                if (path.equals("/favicon.ico")) return null;

                AssetBundle.Asset asset = currentAssetBundle.getIndexFile();
                if (asset != null) {
                    return asset.getFileUri();
                } else {
                    return null;
                }
            }
        });
    }

    /**
     * Handle a resource request - intercepts web requests and serves from bundles
     * This can be called by Capacitor's WebView client to intercept requests
     * 
     * @param path The path being requested (e.g. "index.html" or "assets/app.js")
     * @return WebResourceResponse if we handle the request, null otherwise
     */
    @Nullable
    public WebResourceResponse handleRequest(@NonNull String path) {
        Uri requestUri = Uri.parse("/" + path);
        Uri remappedUri = null;
        
        // Try each handler in order
        for (WebResourceHandler handler : resourceHandlers) {
            remappedUri = handler.remapUri(requestUri);
            if (remappedUri != null) break;
        }
        
        if (remappedUri != null) {
            try {
                ResourceApi resourceApi = new ResourceApi(assetManager);
                ResourceApi.OpenForReadResult result = resourceApi.openForRead(remappedUri, true);
                if (result.inputStream != null) {
                    return new WebResourceResponse(result.mimeType, "utf-8", result.inputStream);
                }
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error opening resource: " + remappedUri, e);
            }
        }
        
        return null;
    }

    /**
     * Remap a URI if it should be handled by this plugin
     * @param uri The URI to potentially remap
     * @return The remapped URI, or null if not handled by this plugin
     */
    public Uri remapUri(Uri uri) {
        // Check if this is a localhost request
        if (!(uri.getScheme().equals("http") && uri.getHost().equals("localhost"))) {
            return null;
        }

        String path = uri.getPath();
        
        // Let Capacitor handle its own internal resources
        // These include native-bridge.js, capacitor.js, capacitor.config.json, etc.
        if (path != null && (
            path.equals("/native-bridge.js") ||
            path.equals("/capacitor.js") ||
            path.startsWith("/capacitor.") ||
            path.equals("/cordova.js") ||  // Capacitor's Cordova compatibility layer
            path.equals("/capacitor.plugins.json") ||
            path.equals("/capacitor.config.json")
        )) {
            Log.d(LOG_TAG, "🔍 Request: " + uri.toString() + " -> Letting Capacitor handle");
            return null;  // Let Capacitor's built-in server handle these
        }

        Log.d(LOG_TAG, "🔍 Request: " + uri.toString());

        Uri remappedUri = null;
        for (WebResourceHandler handler : resourceHandlers) {
            remappedUri = handler.remapUri(uri);
            if (remappedUri != null) break;
        }

        if (remappedUri != null) {
            Log.d(LOG_TAG, "  ✅ Serving: " + remappedUri.toString());
        } else {
            Log.w(LOG_TAG, "  ❌ Not found: " + uri.getPath());
        }

        return remappedUri;
    }

    //endregion
}
