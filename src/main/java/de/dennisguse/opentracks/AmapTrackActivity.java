package de.dennisguse.opentracks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.util.Gcj02Converter;

/**
 * Shows a recorded track on an AMap (Gaode) map.
 */
public class AmapTrackActivity extends AppCompatActivity {

    public static final String EXTRA_TRACK_ID = "de.dennisguse.opentracks.amap.EXTRA_TRACK_ID";

    private static final String PREFS_NAME = "amap_map";
    private static final String PREF_API_KEY = "api_key";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextureMapView mapView;
    private AMap aMap;
    private TextView statusView;
    private Track.Id trackId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        long id = getIntent().getLongExtra(EXTRA_TRACK_ID, -1);
        if (id < 0) {
            finish();
            return;
        }
        trackId = new Track.Id(id);

        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        String apiKey = getStoredApiKey();
        if (!apiKey.isEmpty()) {
            MapsInitializer.setApiKey(apiKey);
        }

        setContentView(R.layout.activity_amap_track);
        mapView = findViewById(R.id.map_view);
        statusView = findViewById(R.id.map_status);
        findViewById(R.id.map_key_button).setOnClickListener(v -> showKeyDialog());

        mapView.onCreate(savedInstanceState);
        aMap = mapView.getMap();
        if (aMap != null) {
            aMap.getUiSettings().setZoomControlsEnabled(false);
            aMap.getUiSettings().setCompassEnabled(true);
            aMap.getUiSettings().setScaleControlsEnabled(true);
        }

        if (apiKey.isEmpty()) {
            showKeyDialog();
        } else {
            loadTrack();
        }
    }

    private String getStoredApiKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = prefs.getString(PREF_API_KEY, "");
        return key == null ? "" : key.trim();
    }

    private void loadTrack() {
        statusView.setText(R.string.map_track_loading);
        statusView.setVisibility(View.VISIBLE);

        ContentProviderUtils contentProviderUtils = new ContentProviderUtils(this);
        executor.execute(() -> {
            List<LatLng> points = new ArrayList<>();
            try (TrackPointIterator iterator = contentProviderUtils.getTrackPointLocationIterator(trackId, null)) {
                while (iterator.hasNext()) {
                    TrackPoint trackPoint = iterator.next();
                    if (!trackPoint.hasLocation()) {
                        continue;
                    }
                    Location location = trackPoint.getLocation();
                    double[] gcj02 = Gcj02Converter.wgs84ToGcj02(location.getLatitude(), location.getLongitude());
                    points.add(new LatLng(gcj02[0], gcj02[1]));
                }
            } catch (Exception e) {
                points.clear();
            }
            runOnUiThread(() -> drawTrack(points));
        });
    }

    private void drawTrack(List<LatLng> points) {
        if (isFinishing() || isDestroyed() || aMap == null) {
            return;
        }

        if (points.isEmpty()) {
            statusView.setText(R.string.map_no_points);
            return;
        }

        statusView.setVisibility(View.GONE);

        if (points.size() > 1) {
            aMap.addPolyline(new PolylineOptions()
                    .addAll(points)
                    .width(12f)
                    .color(0xFF1E88E5));
        }

        aMap.addMarker(new MarkerOptions()
                .position(points.get(0))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .title(getString(R.string.map_start)));
        if (points.size() > 1) {
            aMap.addMarker(new MarkerOptions()
                    .position(points.get(points.size() - 1))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title(getString(R.string.map_end)));
        }

        if (points.size() == 1) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(points.get(0), 16f));
        } else {
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            for (LatLng point : points) {
                builder.include(point);
            }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120));
        }
    }

    private void showKeyDialog() {
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        int smallPadding = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, smallPadding, padding, 0);

        TextView info = new TextView(this);
        info.setText(getString(R.string.amap_key_message, BuildConfig.APPLICATION_ID, getSignatureSha1()));
        info.setTextIsSelectable(true);
        container.addView(info);

        Button copyButton = new Button(this);
        copyButton.setText(R.string.amap_key_copy);
        copyButton.setOnClickListener(v -> copyKeyInfo());
        container.addView(copyButton);

        EditText input = new EditText(this);
        input.setHint(R.string.amap_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(getStoredApiKey());
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.amap_key_title)
                .setView(container)
                .setPositiveButton(R.string.amap_key_save, null)
                .setNegativeButton(R.string.amap_key_cancel, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty()) {
                input.setError(getString(R.string.amap_key_required));
                return;
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_API_KEY, key).apply();
            dialog.dismiss();
            recreate();
        }));
        dialog.setOnDismissListener(d -> {
            if (getStoredApiKey().isEmpty()) {
                finish();
            }
        });
        dialog.show();
    }

    private void copyKeyInfo() {
        String text = "package=" + BuildConfig.APPLICATION_ID + "\nSHA1=" + getSignatureSha1();
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("AMap key info", text));
            Toast.makeText(this, R.string.amap_key_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private String getSignatureSha1() {
        try {
            PackageManager packageManager = getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo packageInfo = packageManager.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = packageInfo.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo packageInfo = packageManager.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = packageInfo.signatures;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(signatures[0].toByteArray());
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(String.format("%02X", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            mapView.onSaveInstanceState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (mapView != null) {
            mapView.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) {
            mapView.onLowMemory();
        }
    }
}
