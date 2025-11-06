package com.banjerluke.capacitormeteorwebapp;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.Bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core implementation of the Meteor webapp functionality for Capacitor,
 * managing asset bundles, updates, and the web view integration.
 *
 * This is adapted from WebAppLocalServer.java from the Cordova implementation.
 * We don't embed our own web server anymore; instead, we use Capacitor's built-in server.
 */
public class CapacitorMeteorWebApp implements AssetBundleManager.Callback {
    private static final String TAG = "CapacitorMeteorWebApp";
    private static final long STARTUP_TIMEOUT_INTERVAL = 30000; // 30 seconds

    private final Context context;
    private final WebAppConfiguration configuration;
    private AssetBundleManager assetBundleManager;
    private AssetBundle currentAssetBundle;

    private void setCurrentAssetBundle(AssetBundle bundle) {
        this.currentAssetBundle = bundle;
        updateConfigurationWithCurrentBundle();
    }
    private AssetBundle pendingAssetBundle;
    private Handler mainHandler;
    private Handler startupTimerHandler;
    private Runnable startupTimerRunnable;
    private boolean switchedToNewVersion = false;

    private File wwwDirectoryURL;
    private File servingDirectoryURL;
    private Bridge bridge;

    private ExecutorService bundleSwitchExecutor;

    public CapacitorMeteorWebApp(Context context, Bridge bridge) {
        Log.i(TAG, "🚀 CapacitorMeteorWebApp constructor called");
        this.context = context;
        this.bridge = bridge;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.startupTimerHandler = new Handler(Looper.getMainLooper());
        this.bundleSwitchExecutor = Executors.newSingleThreadExecutor();
        
        Log.d(TAG, "Context: " + context);
        Log.d(TAG, "Bridge: " + (bridge != null ? "available" : "null"));
        
        this.configuration = new WebAppConfiguration(context);

        // Capacitor copies webDir from capacitor.config.json to android_asset/<webDirName>
        // For example: webDir: "www-dist" -> android_asset/www-dist
        // We need to find which directory contains program.json (Meteor manifest)
        // and extract it to a File location we can access
        
        AssetManager assetManager = context.getAssets();
        String webDirName = null;
        
        try {
            String[] assets = assetManager.list("");
            if (assets != null) {
                Log.d(TAG, "Scanning android_asset for webDir containing program.json...");
                // Scan all directories to find the one with program.json
                for (String asset : assets) {
                    try {
                        String[] subAssets = assetManager.list(asset);
                        if (subAssets != null) {
                            // Check if this directory contains program.json (Meteor manifest)
                            for (String subAsset : subAssets) {
                                if ("program.json".equals(subAsset)) {
                                    webDirName = asset;
                                    Log.i(TAG, "✅ Found webDir in assets: " + asset);
                                    break;
                                }
                            }
                            if (webDirName != null) break;
                        }
                    } catch (IOException e) {
                        // Not a directory, skip
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not list assets: " + e.getMessage());
        }
        
        // Extract assets to a File location we can access
        if (webDirName != null) {
            File extractedWebDir = new File(context.getFilesDir(), "extracted_" + webDirName);
            if (!extractedWebDir.exists() || !new File(extractedWebDir, "program.json").exists()) {
                Log.i(TAG, "📦 Extracting assets from android_asset/" + webDirName + " to " + extractedWebDir.getAbsolutePath());
                try {
                    copyAssetDirectory(assetManager, webDirName, extractedWebDir);
                    wwwDirectoryURL = extractedWebDir;
                    Log.i(TAG, "✅ Extracted webDir successfully");
                } catch (IOException e) {
                    Log.e(TAG, "❌ Failed to extract assets: " + e.getMessage(), e);
                    wwwDirectoryURL = null;
                }
            } else {
                wwwDirectoryURL = extractedWebDir;
                Log.i(TAG, "✅ Using previously extracted webDir: " + extractedWebDir.getAbsolutePath());
            }
        } else {
            Log.e(TAG, "❌ Could not find webDir in android_asset");
            Log.e(TAG, "Expected to find a directory containing program.json");
            Log.e(TAG, "Make sure capacitor.config.json has correct webDir and run 'npx cap sync android'");
            wwwDirectoryURL = null;
        }

        // Initialize asset bundles
        try {
            Log.i(TAG, "📦 Initializing asset bundles...");
            initializeAssetBundles();
            Log.i(TAG, "✅ Asset bundles initialized successfully");
        } catch (WebAppException e) {
            Log.e(TAG, "❌ Failed to initialize asset bundles: " + e.getMessage(), e);
        }

        // Setup startup timer
        setupStartupTimer();
        Log.i(TAG, "✅ CapacitorMeteorWebApp initialization complete");
    }

    private void selectCurrentAssetBundle(AssetBundle initialAssetBundle) {
        // If a last downloaded version has been set and the asset bundle exists,
        // we set it as the current asset bundle
        String lastDownloadedVersion = configuration.getLastDownloadedVersion();
        if (lastDownloadedVersion != null) {
            AssetBundle downloadedAssetBundle = assetBundleManager.downloadedAssetBundleWithVersion(lastDownloadedVersion);
            if (downloadedAssetBundle != null) {
                Log.i(TAG, "📦 Using downloaded asset bundle version: " + lastDownloadedVersion);
                setCurrentAssetBundle(downloadedAssetBundle);
                if (!lastDownloadedVersion.equals(configuration.getLastKnownGoodVersion())) {
                    startStartupTimer();
                }
                return;
            } else {
                Log.w(TAG, "⚠️ Downloaded version " + lastDownloadedVersion + " was configured but bundle not found, falling back to initial bundle");
            }
        }
        Log.i(TAG, "📦 Using initial asset bundle version: " + initialAssetBundle.getVersion());
        setCurrentAssetBundle(initialAssetBundle);
    }

    private void initializeAssetBundles() throws WebAppException {
        assetBundleManager = null;

        // The initial asset bundle consists of the assets bundled with the app
        if (wwwDirectoryURL == null) {
            throw new WebAppException("www directory URL not set");
        }

        AssetBundle initialAssetBundle;
        try {
            initialAssetBundle = new AssetBundle(wwwDirectoryURL);
        } catch (WebAppException e) {
            throw new WebAppException("Could not load initial asset bundle: " + e.getMessage(), e);
        }

        // Downloaded versions are stored in files directory
        File filesDir = context.getFilesDir();
        File versionsDirectoryURL = new File(filesDir, "NoCloud/meteor");

        // Serving directory for organized bundles
        servingDirectoryURL = new File(filesDir, "NoCloud/meteor-serving");

        // If the last seen initial version is different from the currently bundled
        // version, we delete the versions directory and reset configuration
        String lastSeenInitialVersion = configuration.getLastSeenInitialVersion();
        if (lastSeenInitialVersion != null && !lastSeenInitialVersion.equals(initialAssetBundle.getVersion())) {
            deleteDirectory(versionsDirectoryURL);
            deleteDirectory(servingDirectoryURL);
            configuration.reset();
        }

        // We keep track of the last seen initial version
        configuration.setLastSeenInitialVersion(initialAssetBundle.getVersion());

        // Create directories if they don't exist
        if (!versionsDirectoryURL.exists() && !versionsDirectoryURL.mkdirs()) {
            throw new WebAppException("Could not create versions directory");
        }
        if (!servingDirectoryURL.exists() && !servingDirectoryURL.mkdirs()) {
            throw new WebAppException("Could not create serving directory");
        }

        assetBundleManager = new AssetBundleManager(configuration, initialAssetBundle, versionsDirectoryURL);
        assetBundleManager.setCallback(this);

        // Select bundle AFTER validation (configuration.lastDownloadedVersion may have been cleared)
        selectCurrentAssetBundle(initialAssetBundle);

        pendingAssetBundle = null;

        // Organize and serve the current bundle
        setupCurrentBundle();
    }

    private void deleteDirectory(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * Recursively copy an asset directory from android_asset to a File location
     */
    private void copyAssetDirectory(AssetManager assetManager, String assetPath, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Could not create target directory: " + targetDir.getAbsolutePath());
        }

        String[] assets = assetManager.list(assetPath);
        if (assets == null) {
            // Not a directory, treat as file
            InputStream in = assetManager.open(assetPath);
            File outFile = new File(targetDir, new File(assetPath).getName());
            FileOutputStream out = new FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.close();
            return;
        }

        // It's a directory, copy recursively
        for (String asset : assets) {
            String childAssetPath = assetPath.isEmpty() ? asset : assetPath + "/" + asset;
            File childTarget = new File(targetDir, asset);
            
            // Check if it's a directory
            String[] childAssets = assetManager.list(childAssetPath);
            if (childAssets != null && childAssets.length > 0) {
                // It's a directory
                copyAssetDirectory(assetManager, childAssetPath, childTarget);
            } else {
                // It's a file
                try {
                    InputStream in = assetManager.open(childAssetPath);
                    FileOutputStream out = new FileOutputStream(childTarget);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    out.close();
                } catch (IOException e) {
                    // Some assets might not be readable as files, skip them
                    Log.w(TAG, "Could not copy asset " + childAssetPath + ": " + e.getMessage());
                }
            }
        }
    }

    private void setupStartupTimer() {
        startupTimerRunnable = () -> {
            Log.e(TAG, "App startup timed out, reverting to last known good version");
            revertToLastKnownGoodVersion();
        };
    }

    private void updateConfigurationWithCurrentBundle() {
        if (currentAssetBundle == null) return;

        configuration.setAppId(currentAssetBundle.getAppId());
        configuration.setRootUrlString(currentAssetBundle.getRootUrlString());
        configuration.setCordovaCompatibilityVersion(currentAssetBundle.getCordovaCompatibilityVersion());
    }

    private void setupCurrentBundle() {
        if (currentAssetBundle == null) return;

        try {
            File bundleServingDirectory = new File(servingDirectoryURL, currentAssetBundle.getVersion());

            // Remove existing serving directory for this version
            if (bundleServingDirectory.exists()) {
                deleteDirectory(bundleServingDirectory);
            }

            // Organize the bundle for serving
            BundleOrganizer.organizeBundle(currentAssetBundle, bundleServingDirectory);

            // Set Capacitor's server base path to serve from organized bundle
            setServerBasePath(bundleServingDirectory);
        } catch (WebAppException e) {
            Log.e(TAG, "Could not setup current bundle (WebAppError): " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Could not setup current bundle: " + e.getMessage());
        }
    }

    private void setServerBasePath(File path) {
        if (bridge == null) {
            Log.e(TAG, "No Capacitor bridge available for setting server base path");
            return;
        }

        // Use Capacitor's setServerBasePath to change serving directory
        // Must be called on main thread
        mainHandler.post(() -> {
            try {
                bridge.setServerBasePath(path.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Error setting server base path: " + e.getMessage());
            }
        });
    }

    private void startStartupTimer() {
        mainHandler.post(() -> {
            Log.i(TAG, "App startup timer started");
            startupTimerHandler.removeCallbacks(startupTimerRunnable);
            startupTimerHandler.postDelayed(startupTimerRunnable, STARTUP_TIMEOUT_INTERVAL);
        });
    }

    // MARK: - Public Methods

    /**
     * Check for available updates from the Meteor server
     */
    public void checkForUpdates(Runnable completion) {
        String rootURL = configuration.getRootUrlString();
        if (rootURL == null) {
            Log.e(TAG, "Root URL must be configured before checking for updates");
            if (completion != null) {
                completion.run();
            }
            return;
        }

        okhttp3.HttpUrl baseUrl = okhttp3.HttpUrl.parse(rootURL + "/__cordova/");
        if (baseUrl == null) {
            Log.e(TAG, "Invalid root URL: " + rootURL);
            if (completion != null) {
                completion.run();
            }
            return;
        }

        assetBundleManager.checkForUpdates(baseUrl);
        if (completion != null) {
            completion.run();
        }
    }

    /**
     * Notify the plugin that app startup is complete
     */
    public void startupDidComplete(Runnable completion) {
        if (currentAssetBundle == null) {
            Log.e(TAG, "No current asset bundle");
            if (completion != null) {
                completion.run();
            }
            return;
        }

        Log.i(TAG, "App startup completed for bundle " + currentAssetBundle.getVersion());
        startupTimerHandler.removeCallbacks(startupTimerRunnable);

        // If startup completed successfully, we consider a version good
        configuration.setLastKnownGoodVersion(currentAssetBundle.getVersion());

        // Clean up old asset bundles in the background
        bundleSwitchExecutor.execute(() -> {
            try {
                assetBundleManager.removeAllDownloadedAssetBundlesExceptForVersion(currentAssetBundle.getVersion());
            } catch (Exception e) {
                Log.e(TAG, "Could not remove unused asset bundles: " + e.getMessage());
            }
        });

        if (completion != null) {
            completion.run();
        }
    }

    /**
     * Get the current app version
     */
    public String getCurrentVersion() {
        return currentAssetBundle != null ? currentAssetBundle.getVersion() : "unknown";
    }

    /**
     * Check if an update is available and ready to install
     */
    public boolean isUpdateAvailable() {
        return pendingAssetBundle != null;
    }

    /**
     * Reload the app with the latest available version
     */
    public void reload(Runnable completion) {
        bundleSwitchExecutor.execute(() -> {
            performReload(completion);
        });
    }

    private void performReload(Runnable completion) {
        // If there is a pending asset bundle, we switch to it atomically
        if (pendingAssetBundle == null) {
            Log.e(TAG, "No pending version to reload");
            if (completion != null) {
                mainHandler.post(completion);
            }
            return;
        }

        try {
            File bundleServingDirectory = new File(servingDirectoryURL, pendingAssetBundle.getVersion());

            // Remove existing serving directory for this version
            if (bundleServingDirectory.exists()) {
                deleteDirectory(bundleServingDirectory);
            }

            // Organize the bundle
            BundleOrganizer.organizeBundle(pendingAssetBundle, bundleServingDirectory);

            // Make atomic switch
            setCurrentAssetBundle(pendingAssetBundle);
            pendingAssetBundle = null;
            switchedToNewVersion = true;

            // Set new server base path (this will dispatch to main thread)
            setServerBasePath(bundleServingDirectory);

            // Reload the WebView (this will dispatch to main thread)
            mainHandler.post(() -> {
                if (bridge != null && bridge.getWebView() != null) {
                    bridge.getWebView().reload();
                }
            });

            if (completion != null) {
                mainHandler.post(completion);
            }
        } catch (WebAppException e) {
            Log.e(TAG, "Error performing reload: " + e.getMessage());
            if (completion != null) {
                mainHandler.post(completion);
            }
        }
    }

    public void onPageReload() {
        // If there is a pending asset bundle, we make it the current
        if (pendingAssetBundle != null) {
            setCurrentAssetBundle(pendingAssetBundle);
            pendingAssetBundle = null;
        }

        if (switchedToNewVersion) {
            switchedToNewVersion = false;
            startStartupTimer();
        }
    }

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
        else if (currentAssetBundle != null && !currentAssetBundle.equals(assetBundleManager.initialAssetBundle)) {
            pendingAssetBundle = assetBundleManager.initialAssetBundle;
        }

        // Only reload if we have a pending asset bundle to reload
        if (pendingAssetBundle != null) {
            Log.i(TAG, "Reverting to: " + pendingAssetBundle.getVersion());
            reload(null);
        } else {
            Log.w(TAG, "No suitable version to revert to.");
        }
    }

    // MARK: - AssetBundleManager.Callback

    @Override
    public boolean shouldDownloadBundleForManifest(AssetManifest manifest) {
        final String version = manifest.version;

        // No need to redownload the current version
        if (currentAssetBundle != null && currentAssetBundle.getVersion().equals(version)) {
            Log.i(TAG, "Skipping downloading current version: " + version);
            return false;
        }

        // No need to redownload the pending version
        if (pendingAssetBundle != null && pendingAssetBundle.getVersion().equals(version)) {
            Log.i(TAG, "Skipping downloading pending version: " + version);
            return false;
        }

        // Don't download blacklisted versions
        if (configuration.getBlacklistedVersions().contains(version)) {
            Log.w(TAG, "Skipping downloading blacklisted version: " + version);
            return false;
        }

        // Don't download versions potentially incompatible with the bundled native code
        String currentCompatibilityVersion = configuration.getCordovaCompatibilityVersion();
        if (currentCompatibilityVersion != null && !currentCompatibilityVersion.equals(manifest.cordovaCompatibilityVersion)) {
            Log.w(TAG, "Skipping downloading new version because the Cordova platform version or plugin versions have changed and are potentially incompatible");
            return false;
        }

        return true;
    }

    @Override
    public void onFinishedDownloadingAssetBundle(AssetBundle assetBundle) {
        Log.i(TAG, "Finished downloading " + assetBundle.getVersion());
        configuration.setLastDownloadedVersion(assetBundle.getVersion());
        pendingAssetBundle = assetBundle;
        // Notify listeners via bridge
        if (bridge != null) {
            bridge.triggerJSEvent("updateAvailable", "{\"version\":\"" + assetBundle.getVersion() + "\"}");
        }
    }

    @Override
    public void onError(Throwable cause) {
        Log.w(TAG, "Download failure", cause);
        // Notify listeners via bridge
        if (bridge != null) {
            bridge.triggerJSEvent("error", "{\"message\":\"" + cause.getMessage() + "\"}");
        }
    }
}
