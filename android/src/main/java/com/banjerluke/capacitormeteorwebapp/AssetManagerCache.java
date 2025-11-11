package com.banjerluke.capacitormeteorwebapp;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Cache for checking if assets exist in the AssetManager
 * This improves performance by caching the list of assets
 */
class AssetManagerCache {
    private static final String LOG_TAG = "MeteorWebApp";
    
    private final AssetManager assetManager;
    private final Set<String> assetPaths;

    public AssetManagerCache(AssetManager assetManager) throws IOException {
        this.assetManager = assetManager;
        this.assetPaths = new HashSet<>();
        
        // Recursively list all assets
        listAssets("", assetPaths);
    }

    private void listAssets(String path, Set<String> paths) {
        try {
            String[] list = assetManager.list(path);
            if (list != null && list.length > 0) {
                // This is a directory
                for (String file : list) {
                    String fullPath = path.isEmpty() ? file : path + "/" + file;
                    paths.add(fullPath);
                    listAssets(fullPath, paths);
                }
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Error listing assets at path: " + path, e);
        }
    }

    /**
     * Check if an asset exists at the given path
     * @param path The path to check (e.g. "www/index.html" or "public/manifest.json")
     * @return true if the asset exists, false otherwise
     */
    public boolean exists(String path) {
        return assetPaths.contains(path);
    }
}

