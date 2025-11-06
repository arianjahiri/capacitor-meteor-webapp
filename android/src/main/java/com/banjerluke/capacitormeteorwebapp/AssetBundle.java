package com.banjerluke.capacitormeteorwebapp;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AssetBundle {
    private static final String LOG_TAG = "MeteorWebApp";
    private static final Pattern runtimeConfigPattern = Pattern.compile("__meteor_runtime_config__ = JSON.parse\\(decodeURIComponent\\(\"([^\"]*)\"\\)\\)");

    public final class Asset {
        public final String filePath;
        public final String urlPath;
        public final String fileType;
        public final boolean cacheable;
        public final String hash;
        public final String sourceMapUrlPath;

        Asset(String filePath, String urlPath, String fileType, boolean cacheable, String hash, String sourceMapUrlPath) {
            this.filePath = filePath;
            this.urlPath = urlPath;
            this.fileType = fileType;
            this.cacheable = cacheable;
            this.hash = hash;
            this.sourceMapUrlPath = sourceMapUrlPath;
        }

        public File getFile() {
            return new File(AssetBundle.this.directory, filePath);
        }

        public File getTemporaryFile() throws IOException {
            File file = this.getFile();
            return File.createTempFile(file.getName(), "tmp");
        }

        @Override
        public String toString() {
            return urlPath;
        }
    }

    private File directory;
    private final AssetBundle parentAssetBundle;

    private final String version;
    private final String cordovaCompatibilityVersion;

    private Map<String, Asset> ownAssetsByURLPath;
    private Asset indexFile;

    private RuntimeConfig runtimeConfig;

    public AssetBundle(File directory) throws WebAppException {
        this(directory, null, null);
    }

    public AssetBundle(File directory, AssetBundle parentAssetBundle) throws WebAppException {
        this(directory, null, parentAssetBundle);
    }

    public AssetBundle(File directory, AssetManifest manifest, AssetBundle parentAssetBundle) throws WebAppException {
        Log.w(LOG_TAG, "Loading asset bundle from directory " + directory.getAbsolutePath());

        this.directory = directory;
        this.parentAssetBundle = parentAssetBundle;

        if (manifest == null) {
            manifest = loadAssetManifest();
        }

        version = manifest.version;
        cordovaCompatibilityVersion = manifest.cordovaCompatibilityVersion;

        ownAssetsByURLPath = new HashMap<String, Asset>();
        for (AssetManifest.Entry entry : manifest.entries) {
            // Remove query parameters from url path
            String urlPath = removeQueryString(entry.urlPath);

            if (parentAssetBundle == null || parentAssetBundle.cachedAssetForUrlPath(urlPath, entry.hash) == null) {
                Asset asset = new Asset(entry.filePath, urlPath, entry.fileType, entry.cacheable, entry.hash, entry.sourceMapUrlPath);
                addAsset(asset);
            }

            if (entry.sourceMapFilePath != null && entry.sourceMapUrlPath != null) {
                if (parentAssetBundle == null || parentAssetBundle.cachedAssetForUrlPath(entry.sourceMapUrlPath, null) == null) {
                    Asset sourceMap = new Asset(entry.sourceMapFilePath, entry.sourceMapUrlPath, "json", true, null, null);
                    addAsset(sourceMap);
                }
            }
        }

        Asset indexFile = new Asset("index.html", "/", "html", false, null, null);
        addAsset(indexFile);
        this.indexFile = indexFile;
    }

    private String removeQueryString(String urlPath) {
        int queryIndex = urlPath.indexOf('?');
        return queryIndex >= 0 ? urlPath.substring(0, queryIndex) : urlPath;
    }

    protected void addAsset(Asset asset) {
        ownAssetsByURLPath.put(asset.urlPath, asset);
    }

    public Set<Asset> getOwnAssets() {
        return new HashSet<Asset>(ownAssetsByURLPath.values());
    }

    public Asset assetForUrlPath(String urlPath) {
        Asset asset = ownAssetsByURLPath.get(urlPath);
        if (asset == null && parentAssetBundle != null) {
            Log.d(LOG_TAG, "Asset " + urlPath + " not found in bundle " + version + ":" + directory.getAbsolutePath() + ", serving from parent bundle");
            asset = parentAssetBundle.assetForUrlPath(urlPath);
        } else if (asset == null) {
            Log.w(LOG_TAG, "Asset " + urlPath + " not found in bundle " + version + ":" + directory.getAbsolutePath() + ", no parent bundle");
        } else {
            Log.w(LOG_TAG, "Asset " + urlPath + " found in bundle " + version + ":" + directory.getAbsolutePath());
        }
        return asset;
    }

    public Asset cachedAssetForUrlPath(String urlPath, String hash) {
        Asset asset = ownAssetsByURLPath.get(urlPath);

        if (asset == null) return null;

        // If the asset is not cacheable, we require a matching hash
        if ((asset.cacheable && hash == null) || (asset.hash != null && asset.hash.equals(hash))) {
            return asset;
        }

        return null;
    }

    public String getVersion() {
        return version;
    }

    public String getCordovaCompatibilityVersion() {
        return cordovaCompatibilityVersion;
    }

    public Asset getIndexFile() {
        return indexFile;
    }

    public File getDirectory() {
        return directory;
    }

    public AssetBundle getParentAssetBundle() {
        return parentAssetBundle;
    }

    public RuntimeConfig getRuntimeConfig() {
        if (runtimeConfig == null && indexFile != null) {
            try {
                File indexFileURL = indexFile.getFile();
                runtimeConfig = loadRuntimeConfigFromIndexFile(indexFileURL);
            } catch (WebAppException e) {
                Log.e("AssetBundle", "Error loading runtime config: " + e.getMessage());
            }
        }
        return runtimeConfig;
    }

    public String getAppId() {
        RuntimeConfig config = getRuntimeConfig();
        return config != null ? config.getAppId() : null;
    }

    public String getRootUrlString() {
        RuntimeConfig config = getRuntimeConfig();
        return config != null ? config.getRootUrlString() : null;
    }

    void didMoveToDirectory(File directory) {
        this.directory = directory;
    }

    private AssetManifest loadAssetManifest() throws WebAppException {
        File manifestFile = new File(directory, "program.json");
        try {
            String string = IOUtils.stringFromInputStream(new FileInputStream(manifestFile));
            return new AssetManifest(string);
        } catch (IOException e) {
            throw new WebAppException("Error loading asset manifest", e);
        }
    }

    public static RuntimeConfig loadRuntimeConfigFromIndexFile(File indexFile) throws WebAppException {
        try {
            String string = IOUtils.stringFromInputStream(new FileInputStream(indexFile));
            Matcher matcher = runtimeConfigPattern.matcher(string);
            if (!matcher.find()) {
                throw new WebAppException("Could not find runtime config in index file");
            }
            String runtimeConfigString = URLDecoder.decode(matcher.group(1), "UTF-8");
            return new RuntimeConfig(new org.json.JSONObject(runtimeConfigString));
        } catch (IOException e) {
            throw new WebAppException("Error loading index file", e);
        } catch (IllegalStateException e) {
            throw new WebAppException("Could not find runtime config in index file", e);
        } catch (JSONException e) {
            throw new WebAppException("Error parsing runtime config", e);
        }
    }

    public static class RuntimeConfig {
        private final JSONObject json;

        public RuntimeConfig(JSONObject json) {
            this.json = json;
        }

        public String getAppId() {
            return json.optString("appId", null);
        }

        public String getRootUrlString() {
            return json.optString("ROOT_URL", null);
        }

        public String getAutoupdateVersionCordova() {
            return json.optString("autoupdateVersionCordova", null);
        }
    }
}
