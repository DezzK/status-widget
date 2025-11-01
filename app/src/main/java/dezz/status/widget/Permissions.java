package dezz.status.widget;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class Permissions {
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    public static boolean allPermissionsGranted(Context context) {
        return checkOverlayPermission(context) && checkForMissingPermissions(context).isEmpty();
    }

    public static List<String> checkForMissingPermissions(Context context) {
        List<String> missingPermissions = new ArrayList<>();

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        return missingPermissions;
    }

    public static boolean checkOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }
}
