package com.banjerluke.capacitormeteorwebapp;

import android.net.Uri;

/**
 * Interface for handling web resource requests
 */
interface WebResourceHandler {
    /**
     * Remap a URI to the actual resource location
     * @param uri The requested URI
     * @return The remapped URI, or null if this handler doesn't handle the request
     */
    Uri remapUri(Uri uri);
}

