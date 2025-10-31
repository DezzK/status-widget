package dezz.status.widget;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class Permissions {
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    public static List<String> checkForMissingPermissions(Context context) {
        return checkForMissingPermissions(context, false);
    }

    public static List<String> checkForMissingPermissions(Context context, boolean humanReadable) {
        List<String> missingPermissions = new ArrayList<>();

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(humanReadable ? getPermissionName(context, permission) : permission);
            }
        }

        return missingPermissions;
    }

    public static boolean checkOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    private static String getPermissionName(Context context, String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION ->
                    context.getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION ->
                    context.getString(R.string.permission_coarse_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }
}
