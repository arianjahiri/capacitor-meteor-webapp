package com.banjerluke.capacitormeteorwebapp;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Simple replacement for CordovaResourceApi to handle file and asset URIs
 * for Capacitor implementation.
 */
class ResourceApi {
    private final AssetManager assetManager;

    public ResourceApi(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    /**
     * Opens an InputStream for reading from a URI
     */
    public OpenForReadResult openForRead(Uri uri, boolean skipThreadCheck) throws IOException {
        String scheme = uri.getScheme();
        
        if ("file".equals(scheme)) {
            // Handle file:// URIs
            String path = uri.getPath();
            
            // Check if this is an android_asset path
            if (path != null && path.startsWith("/android_asset/")) {
                // Remove /android_asset/ prefix
                String assetPath = path.substring("/android_asset/".length());
                InputStream inputStream = assetManager.open(assetPath);
                String mimeType = getMimeType(uri);
                return new OpenForReadResult(uri, inputStream, mimeType, -1, null);
            } else {
                // Regular file path
                File file = new File(path);
                if (!file.exists()) {
                    return new OpenForReadResult(uri, null, null, 0, null);
                }
                InputStream inputStream = new FileInputStream(file);
                String mimeType = getMimeType(uri);
                long length = file.length();
                return new OpenForReadResult(uri, inputStream, mimeType, length, null);
            }
        }
        
        throw new IOException("Unsupported URI scheme: " + scheme);
    }

    /**
     * Maps a URI to a File object if possible
     */
    public File mapUriToFile(Uri uri) {
        if (!"file".equals(uri.getScheme())) {
            return null;
        }
        
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        
        // Can't map android_asset paths to files
        if (path.startsWith("/android_asset/")) {
            return null;
        }
        
        return new File(path);
    }

    /**
     * Gets the MIME type for a URI based on file extension
     */
    public String getMimeType(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            return "application/octet-stream";
        }
        
        String extension = "";
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = path.substring(dotIndex + 1);
        }
        
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    /**
     * Result object for openForRead operations
     */
    public static class OpenForReadResult {
        public final Uri uri;
        public final InputStream inputStream;
        public final String mimeType;
        public final long length;
        public final String charset;

        public OpenForReadResult(Uri uri, InputStream inputStream, String mimeType, long length, String charset) {
            this.uri = uri;
            this.inputStream = inputStream;
            this.mimeType = mimeType;
            this.length = length;
            this.charset = charset;
        }
    }
}

