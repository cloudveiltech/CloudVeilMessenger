package org.cloudveil.messenger.util;

import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CloudVeilUriFilter {
    private static final List<String> IGNORED_SCHEMES_AND_PATHS = Arrays.asList(
            "tg://addemoji",
            "tg://addlist",
            "tg://addstickers",
            "tg://folder"
    );

    private static final List<String> IGNORED_SCHEMES = Collections.singletonList(
            "tonsite"
    );

    private static final List<String> IGNORED_PATHS = Arrays.asList(
            "addemoji",
            "addlist",
            "addstickers",
            "folder"
    );

    private static final List<String> IGNORED_DOMAINS = Collections.singletonList(
            ".ton"
    );

    public static boolean shouldIgnoreUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            return shouldIgnoreUri(uri);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldIgnoreUri(Uri uri) {

        if (uri.getScheme() != null && IGNORED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            return true;
        }

        // Check if the URI matches any of the ignoredSchemesAndPaths ones
        for (String ignored : IGNORED_SCHEMES_AND_PATHS) {
            if (uri.toString().startsWith(ignored)) {
                return true;
            }
        }

        // Ignore ".ton" domains
        for (String ignored : IGNORED_DOMAINS) {
            String host = uri.getHost();
            if (host != null && host.toLowerCase().endsWith(ignored)) {
                return true;
            }
        }

        // Ignore paths that are in the ignorePaths list
        for (String ignored : IGNORED_PATHS) {
            String path = uri.getPath();
            if (path != null
                    && path.toLowerCase().replace("/","").startsWith(ignored)) {
                return true;
            }
        }

        return false;
    }

}