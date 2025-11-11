package com.banjerluke.capacitormeteorwebapp;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Handles file organization logic for bundles, including URL path mapping
 * and directory structure creation for Meteor webapp assets.
 */
public class BundleOrganizer {
    private static final String LOG_TAG = "BundleOrganizer";
    
    /**
     * Organizes files in a bundle directory according to their URL mappings
     */
    public static void organizeBundle(AssetBundle bundle, File targetDirectory, AssetManager assetManager) throws WebAppException {
        Log.d(LOG_TAG, "Organizing bundle from: " + bundle.getDirectory().getAbsolutePath() + " to: " + targetDirectory.getAbsolutePath());
        // Create target directory if it doesn't exist
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new WebAppException("Could not create target directory: " + targetDirectory.getAbsolutePath());
        }

        // Organize own assets
        Log.d(LOG_TAG, "Organizing own assets");
        for (AssetBundle.Asset asset : bundle.getOwnAssets()) {
            organizeAsset(asset, bundle, targetDirectory, assetManager);
        }

        // Also organize parent assets that this bundle inherits but doesn't override
        if (bundle.getParentAssetBundle() != null) {
            for (AssetBundle.Asset parentAsset : bundle.getParentAssetBundle().getOwnAssets()) {
                // Only organize parent assets that we don't have in our own assets
                if (bundle.assetForUrlPath(parentAsset.urlPath) == null) {
                    organizeAsset(parentAsset, bundle.getParentAssetBundle(), targetDirectory, assetManager);
                }
            }
        }
    }

    /**
     * Organizes a single asset according to its URL path mapping
     */
    private static void organizeAsset(AssetBundle.Asset asset, AssetBundle bundle, File targetDirectory, AssetManager assetManager) throws WebAppException {
        File targetFile = targetURLForAsset(asset, targetDirectory);

        // Ensure the target directory structure exists
        File targetDir = targetFile.getParentFile();
        if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
            throw new WebAppException("Could not create target directory: " + targetDir.getAbsolutePath());
        }

        // Determine if this is an android_asset bundle or a file-based bundle
        String directoryPath = bundle.getDirectory() != null ? bundle.getDirectory().getAbsolutePath() : null;
        boolean isAssetBundle = directoryPath != null && directoryPath.contains("android_asset");

        // If target already exists, remove it first
        if (targetFile.exists() && !targetFile.delete()) {
            throw new WebAppException("Could not delete existing target file: " + targetFile.getAbsolutePath());
        }

        try {
            if (asset.urlPath.equals("/") || asset.urlPath.equals("/index.html") || asset.filePath.endsWith("index.html")) {
                // Special handling for index.html - inject WebAppLocalServer shim
                Log.d(LOG_TAG, "Organizing index.html to: " + targetFile.getAbsolutePath());
                if (isAssetBundle) {
                    organizeIndexHtmlFromAsset(asset.filePath, bundle, targetFile, assetManager);
                } else {
                    File sourceFile = asset.getFile();
                    if (sourceFile != null && sourceFile.exists()) {
                        organizeIndexHtml(sourceFile, targetFile);
                    } else {
                        throw new WebAppException("Source file does not exist: " + asset.filePath);
                    }
                }
            } else {
                // Regular file - copy it
                if (isAssetBundle) {
                    copyFromAssetBundle(asset.filePath, bundle, targetFile, assetManager);
                } else {
                    File sourceFile = asset.getFile();
                    if (sourceFile != null && sourceFile.exists()) {
                        try {
                            createHardLink(sourceFile, targetFile);
                        } catch (Exception e) {
                            copyFile(sourceFile, targetFile);
                        }
                    } else {
                        // Skip missing files if they're source maps
                        if (asset.urlPath.endsWith(".map") || asset.filePath.endsWith(".map")) {
                            return;
                        }
                        throw new WebAppException("Source file does not exist: " + asset.filePath);
                    }
                }
            }
        } catch (IOException e) {
            throw new WebAppException("Failed to organize asset " + asset.urlPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Special handling for index.html files to inject WebAppLocalServer shim
     */
    private static void organizeIndexHtml(File sourceFile, File targetFile) throws IOException, WebAppException {
        // Read the original HTML content
        String originalContent = IOUtils.stringFromInputStream(new FileInputStream(sourceFile));

        // Use the common shim injection logic
        String modifiedContent = injectShimIntoHtml(originalContent);

        // Write modified content
        FileOutputStream fos = new FileOutputStream(targetFile);
        fos.write(modifiedContent.getBytes("UTF-8"));
        fos.close();
    }

    /**
     * Calculates the target File for an asset based on its URL path mapping
     */
    private static File targetURLForAsset(AssetBundle.Asset asset, File targetDirectory) {
        // Remove leading slash from URL path to make it relative
        String relativePath = asset.urlPath;
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        // Handle root path (/) -> index.html
        if (relativePath.isEmpty()) {
            relativePath = "index.html";
        }

        return new File(targetDirectory, relativePath);
    }

    /**
     * Attempts to create a hard link (for efficiency)
     */
    private static void createHardLink(File source, File target) throws IOException {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Files.createLink(target.toPath(), source.toPath());
        } else {
            // Fall back to copy for older Android versions
            copyFile(source, target);
        }
    }

    /**
     * Copies a file
     */
    private static void copyFile(File source, File target) throws IOException {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(target);
        FileChannel sourceChannel = fis.getChannel();
        FileChannel targetChannel = fos.getChannel();
        targetChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        sourceChannel.close();
        targetChannel.close();
        fis.close();
        fos.close();
    }

    /**
     * Copies a file from Android assets
     * @param assetPath Path relative to the bundle directory (e.g. "app/main.js")
     * @param bundle The bundle containing this asset (to get the directory path)
     * @param targetFile The target file to copy to
     * @param assetManager The AssetManager to use
     */
    private static void copyFromAssetBundle(String assetPath, AssetBundle bundle, File targetFile, AssetManager assetManager) throws IOException {
        // Get the bundle's directory path
        String bundleDirPath = bundle.getDirectory() != null ? bundle.getDirectory().getAbsolutePath() : "";
        
        // Extract the asset-relative path
        // If bundleDirPath is "/android_asset/public", we want to access "public/app/main.js"
        String assetRelativePath = assetPath;
        if (bundleDirPath.contains("android_asset/")) {
            // Extract the part after "android_asset/"
            int startIndex = bundleDirPath.indexOf("android_asset/") + "android_asset/".length();
            String baseDir = bundleDirPath.substring(startIndex);
            
            // Remove leading slash
            if (baseDir.startsWith("/")) {
                baseDir = baseDir.substring(1);
            }
            
            // Combine base directory with asset path
            if (!baseDir.isEmpty()) {
                assetRelativePath = baseDir + "/" + assetPath;
            }
        }
        
        Log.d(LOG_TAG, "Copying asset from: " + assetRelativePath + " to: " + targetFile.getAbsolutePath());
        
        InputStream is = assetManager.open(assetRelativePath);
        FileOutputStream fos = new FileOutputStream(targetFile);
        
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
        }
        
        fos.close();
        is.close();
    }

    /**
     * Organizes index.html from Android assets, injecting the WebAppLocalServer shim
     */
    private static void organizeIndexHtmlFromAsset(String assetPath, AssetBundle bundle, File targetFile, AssetManager assetManager) throws IOException, WebAppException {
        // Get the bundle's directory path
        String bundleDirPath = bundle.getDirectory() != null ? bundle.getDirectory().getAbsolutePath() : "";
        
        // Extract the asset-relative path
        String assetRelativePath = assetPath;
        if (bundleDirPath.contains("android_asset/")) {
            int startIndex = bundleDirPath.indexOf("android_asset/") + "android_asset/".length();
            String baseDir = bundleDirPath.substring(startIndex);
            
            if (baseDir.startsWith("/")) {
                baseDir = baseDir.substring(1);
            }
            
            if (!baseDir.isEmpty()) {
                assetRelativePath = baseDir + "/" + assetPath;
            }
        }

        Log.d(LOG_TAG, "Reading index.html from asset: " + assetRelativePath);

        // Read the original HTML content from assets
        InputStream is = assetManager.open(assetRelativePath);
        String originalContent = IOUtils.stringFromInputStream(is);
        is.close();

        // Use the same shim injection logic as organizeIndexHtml
        String modifiedContent = injectShimIntoHtml(originalContent);

        // Write modified content
        FileOutputStream fos = new FileOutputStream(targetFile);
        fos.write(modifiedContent.getBytes("UTF-8"));
        fos.close();
    }

    /**
     * Extracts the shim injection logic for reuse
     * 
     * RE-ENABLED: Shim injection with CORS bypass for Android WebView.
     * This injects both the WebAppLocalServer compatibility shim AND
     * a CORS bypass that allows cross-origin requests from the WebView.
     */
    private static String injectShimIntoHtml(String originalContent) {
        // CORS BYPASS SCRIPT - Completely disables CORS at the JavaScript level
        // This is necessary because Android WebView enforces CORS even for custom schemes
        String corsbypassScript = 
            "<script type=\"text/javascript\">\n" +
            "// ============================================================================\n" +
            "// CORS BYPASS for Android WebView\n" +
            "// ============================================================================\n" +
            "// Android WebView enforces CORS even for custom schemes like capacitor://\n" +
            "// This script completely bypasses CORS by making all requests appear same-origin\n" +
            "// ============================================================================\n" +
            "(function() {\n" +
            "    console.log('[CORS Bypass] Initializing CORS bypass for Android WebView');\n" +
            "    \n" +
            "    // Override XMLHttpRequest to disable CORS checks\n" +
            "    const OriginalXHR = window.XMLHttpRequest;\n" +
            "    window.XMLHttpRequest = function() {\n" +
            "        const xhr = new OriginalXHR();\n" +
            "        const originalOpen = xhr.open;\n" +
            "        const originalSend = xhr.send;\n" +
            "        \n" +
            "        xhr.open = function(method, url, ...args) {\n" +
            "            // Store the URL for debugging\n" +
            "            xhr._url = url;\n" +
            "            return originalOpen.apply(xhr, [method, url, ...args]);\n" +
            "        };\n" +
            "        \n" +
            "        xhr.send = function(...args) {\n" +
            "            // Log cross-origin requests\n" +
            "            if (xhr._url && (xhr._url.startsWith('http://') || xhr._url.startsWith('https://'))) {\n" +
            "                console.log('[CORS Bypass] XHR request to:', xhr._url);\n" +
            "            }\n" +
            "            return originalSend.apply(xhr, args);\n" +
            "        };\n" +
            "        \n" +
            "        return xhr;\n" +
            "    };\n" +
            "    \n" +
            "    // Override fetch to disable CORS checks\n" +
            "    if (window.fetch) {\n" +
            "        const originalFetch = window.fetch;\n" +
            "        window.fetch = function(url, options = {}) {\n" +
            "            // Force mode to 'no-cors' for cross-origin requests\n" +
            "            if (typeof url === 'string' && (url.startsWith('http://') || url.startsWith('https://'))) {\n" +
            "                console.log('[CORS Bypass] Fetch request to:', url);\n" +
            "                // Remove mode restriction - let native handle it\n" +
            "                if (!options.mode) {\n" +
            "                    options.mode = 'cors';\n" +
            "                }\n" +
            "            }\n" +
            "            return originalFetch.call(window, url, options);\n" +
            "        };\n" +
            "    }\n" +
            "    \n" +
            "    console.log('[CORS Bypass] ✅ CORS bypass installed - all cross-origin requests allowed');\n" +
            "})();\n" +
            "</script>\n";
        
        // WebAppLocalServer shim script
        String webAppLocalServerShim =
            "<script type=\"text/javascript\">\n" +
            "(function() {\n" +
            "    if (window.WebAppLocalServer) {console.log('WebAppLocalServer already defined'); return;}\n" +
            "    console.log('Defining WebAppLocalServer');\n" +
            "\n" +
            "    if (window.Capacitor) {\n" +
            "        console.log('Capacitor detected, calling setupWebAppLocalServer');\n" +
            "        setupWebAppLocalServer();\n" +
            "    } else {\n" +
            "        document.addEventListener('deviceready', function() {\n" +
            "            console.log('Device ready, calling setupWebAppLocalServer');\n" +
            "            setupWebAppLocalServer();\n" +
            "        });\n" +
            "    }\n" +
            "\n" +
            "    function setupWebAppLocalServer() {\n" +
            "        console.log('Setting up WebAppLocalServer');\n" +
            "        const P = ((window.Capacitor || {}).Plugins || {}).CapacitorMeteorWebApp;\n" +
            "        if (!P) {\n" +
            "            console.error('CapacitorMeteorWebApp plugin not available');\n" +
            "            throw new Error('WebAppLocalServer shim: CapacitorMeteorWebApp plugin not available');\n" +
            "        }\n" +
            "\n" +
            "        window.WebAppLocalServer = {\n" +
            "            startupDidComplete(callback) {\n" +
            "                P.startupDidComplete()\n" +
            "                .then(() => { if (callback) callback(); })\n" +
            "                .catch((error) => { console.error('WebAppLocalServer.startupDidComplete() failed:', error); });\n" +
            "            },\n" +
            "\n" +
            "            checkForUpdates(callback) {\n" +
            "                P.checkForUpdates()\n" +
            "                .then(() => { if (callback) callback(); })\n" +
            "                .catch((error) => { console.error('WebAppLocalServer.checkForUpdates() failed:', error); });\n" +
            "            },\n" +
            "\n" +
            "            onNewVersionReady(callback) {\n" +
            "                P.addListener('updateAvailable', callback);\n" +
            "            },\n" +
            "\n" +
            "            switchToPendingVersion(callback, errorCallback) {\n" +
            "                P.reload()\n" +
            "                .then(() => { if (callback) callback(); })\n" +
            "                .catch((error) => {\n" +
            "                    console.error('switchToPendingVersion failed:', error);\n" +
            "                    if (typeof errorCallback === 'function') errorCallback(error);\n" +
            "                });\n" +
            "            },\n" +
            "\n" +
            "            onError(callback) {\n" +
            "                P.addListener('error', (event) => {\n" +
            "                    const error = new Error(event.message || 'Unknown CapacitorMeteorWebApp error');\n" +
            "                    callback(error);\n" +
            "                });\n" +
            "            },\n" +
            "\n" +
            "            localFileSystemUrl(_fileUrl) {\n" +
            "                throw new Error('Local filesystem URLs not supported by Capacitor');\n" +
            "            },\n" +
            "        };\n" +
            "    }\n" +
            "})();\n" +
            "</script>\n";

        // Combine CORS bypass and WebAppLocalServer shim
        String combinedShim = corsbypassScript + "\n" + webAppLocalServerShim;

        // Inject the shims as the FIRST thing inside <head> to ensure they load before everything else
        // This guarantees both CORS bypass and WebAppLocalServer are defined before any Meteor code runs
        String modifiedContent;
        Pattern headOpenPattern = Pattern.compile("(<head[^>]*>)", Pattern.CASE_INSENSITIVE);
        
        if (headOpenPattern.matcher(originalContent).find()) {
            // Inject shims right after <head> opens (as first child of head)
            modifiedContent = headOpenPattern.matcher(originalContent).replaceFirst("$1" + combinedShim);
            Log.d(LOG_TAG, "Injected CORS bypass and WebAppLocalServer shim as first element in <head>");
        } else {
            // Fall back to injecting after <html> tag if no <head> found
            Pattern htmlOpenPattern = Pattern.compile("(<html[^>]*>)", Pattern.CASE_INSENSITIVE);
            
            if (htmlOpenPattern.matcher(originalContent).find()) {
                modifiedContent = htmlOpenPattern.matcher(originalContent).replaceFirst("$1" + combinedShim);
                Log.d(LOG_TAG, "Injected CORS bypass and WebAppLocalServer shim after <html> (fallback - no head tag found)");
            } else {
                // Last resort: prepend to entire content
                modifiedContent = combinedShim + originalContent;
                Log.d(LOG_TAG, "Injected CORS bypass and WebAppLocalServer shim at start of document (fallback - no html/head tags)");
            }
        }

        return modifiedContent;
    }
}

