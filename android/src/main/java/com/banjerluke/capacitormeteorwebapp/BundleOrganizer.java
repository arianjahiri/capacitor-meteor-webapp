package com.banjerluke.capacitormeteorwebapp;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
    public static void organizeBundle(AssetBundle bundle, File targetDirectory) throws WebAppException {
        // Create target directory if it doesn't exist
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new WebAppException("Could not create target directory: " + targetDirectory.getAbsolutePath());
        }

        // Organize own assets
        for (AssetBundle.Asset asset : bundle.getOwnAssets()) {
            organizeAsset(asset, bundle.getDirectory(), targetDirectory);
        }

        // Also organize parent assets that this bundle inherits but doesn't override
        if (bundle.getParentAssetBundle() != null) {
            for (AssetBundle.Asset parentAsset : bundle.getParentAssetBundle().getOwnAssets()) {
                // Only organize parent assets that we don't have in our own assets
                if (bundle.assetForUrlPath(parentAsset.urlPath) == null) {
                    organizeAsset(parentAsset, bundle.getParentAssetBundle().getDirectory(), targetDirectory);
                }
            }
        }
    }

    /**
     * Organizes a single asset according to its URL path mapping
     */
    private static void organizeAsset(AssetBundle.Asset asset, File sourceDirectory, File targetDirectory) throws WebAppException {
        File sourceFile = new File(sourceDirectory, asset.filePath);
        File targetFile = targetURLForAsset(asset, targetDirectory);

        // Ensure the target directory structure exists
        File targetDir = targetFile.getParentFile();
        if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
            throw new WebAppException("Could not create target directory: " + targetDir.getAbsolutePath());
        }

        // Check if source file exists
        if (!sourceFile.exists()) {
            if (asset.urlPath.endsWith(".map") || asset.filePath.endsWith(".map")) {
                // Skip missing source maps - they may not be served in production
                return;
            }
            throw new WebAppException("Source file does not exist: " + sourceFile.getAbsolutePath());
        }

        // If target already exists, remove it first
        if (targetFile.exists() && !targetFile.delete()) {
            throw new WebAppException("Could not delete existing target file: " + targetFile.getAbsolutePath());
        }

        try {
            if (asset.urlPath.equals("/") || asset.urlPath.equals("/index.html") || sourceFile.getName().equals("index.html")) {
                // Special handling for index.html - inject WebAppLocalServer shim
                organizeIndexHtml(sourceFile, targetFile);
            } else {
                // Try to create hard link first (for efficiency), fall back to copy
                try {
                    createHardLink(sourceFile, targetFile);
                } catch (Exception e) {
                    // Hard link failed, try copying instead
                    copyFile(sourceFile, targetFile);
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

        // WebAppLocalServer compatibility shim for Capacitor
        // Provides the same API as cordova-plugin-meteor-webapp
        String shimScript = "\n<script>\n" +
            "(function() {\n" +
            "    if (window.WebAppLocalServer) return;\n" +
            "\n" +
            "    if (window.Capacitor) {\n" +
            "        setupWebAppLocalServer();\n" +
            "    } else {\n" +
            "        document.addEventListener('deviceready', function() {\n" +
            "            setupWebAppLocalServer();\n" +
            "        });\n" +
            "    }\n" +
            "\n" +
            "    function setupWebAppLocalServer() {\n" +
            "        const P = ((window.Capacitor || {}).Plugins || {}).CapacitorMeteorWebApp;\n" +
            "        if (!P) {\n" +
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

        // Inject the shim before closing </head> tag, or before </body> if no head
        String modifiedContent;
        Pattern headClosePattern = Pattern.compile("(?i)</head>");
        Pattern bodyClosePattern = Pattern.compile("(?i)</body>");
        
        if (headClosePattern.matcher(originalContent).find()) {
            modifiedContent = originalContent.replaceFirst("(?i)</head>", shimScript + "</head>");
        } else if (bodyClosePattern.matcher(originalContent).find()) {
            modifiedContent = originalContent.replaceFirst("(?i)</body>", shimScript + "</body>");
        } else {
            // Just append to the end if we can't find head or body tags
            modifiedContent = originalContent + shimScript;
        }

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
}

