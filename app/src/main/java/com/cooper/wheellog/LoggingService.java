package com.cooper.wheellog;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.FileUtil;
import com.cooper.wheellog.utils.PermissionsUtil;
import com.cooper.wheellog.utils.SettingsUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import timber.log.Timber;

public class LoggingService extends Service
{
    private static LoggingService instance = null;
    SimpleDateFormat sdf;
    private String filename;
    private Location mLocation;
    private LocationManager mLocationManager;
    private boolean logLocationData = false;

    public static boolean isInstanceCreated() {
        return instance != null;
    }

    private final BroadcastReceiver mBluetoothUpdateReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            updateFile();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        instance = this;
        registerReceiver(mBluetoothUpdateReceiver, new IntentFilter(Constants.ACTION_WHEEL_DATA_AVAILABLE));

        if (!PermissionsUtil.checkExternalFilePermission(this)) {
            showToast(R.string.logging_error_no_storage_permission);
            stopSelf();
            return START_STICKY;
        }

        if (!isExternalStorageReadable() || !isExternalStorageWritable()) {
            showToast(R.string.logging_error_storage_unavailable);
            stopSelf();
            return START_STICKY;
        }

        logLocationData = SettingsUtil.getLogLocation(this);

        if (logLocationData && !PermissionsUtil.checkLocationPermission(this)) {
            showToast(R.string.logging_error_no_location_permission);
            logLocationData = false;
        }

        sdf = new SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", Locale.US);

        SimpleDateFormat sdFormatter = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);

        filename = sdFormatter.format(new Date()) + ".csv";
        File file = FileUtil.getFile(filename);
        if (file == null) {
            stopSelf();
            return START_STICKY;
        }

        if (logLocationData) {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

            // Getting GPS Provider status
            boolean isGPSEnabled = mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);

            // Getting Network Provider status
            boolean isNetworkEnabled = mLocationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            // Getting if the users wants to use GPS
            boolean useGPS = SettingsUtil.getUseGPS(this);

            if (!isGPSEnabled && !isNetworkEnabled) {
                logLocationData = false;
                mLocationManager = null;
                showToast(R.string.logging_error_all_location_providers_disabled);
            } else if (useGPS && !isGPSEnabled) {
                useGPS = false;
                showToast(R.string.logging_error_gps_disabled);
            } else if (!useGPS && !isNetworkEnabled) {
                logLocationData = false;
                mLocationManager = null;
                showToast(R.string.logging_error_network_disabled);
            }

            if (logLocationData) {
                FileUtil.writeLine(filename, "date,time,latitude,longitude,speed,voltage,current,power,battery_level,distance,temperature");
                mLocation = getLastBestLocation();
                String locationProvider = LocationManager.NETWORK_PROVIDER;
                if (useGPS)
                    locationProvider = LocationManager.GPS_PROVIDER;
                // Acquire a reference to the system Location Manager
                mLocationManager.requestLocationUpdates(locationProvider, 100, 0, locationListener);
            } else
                FileUtil.writeLine(filename, "date,time,speed,voltage,current,power,battery_level,distance,temperature");
        }

        Intent serviceIntent = new Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED);
        serviceIntent.putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, file.getAbsolutePath());
        serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true);
        sendBroadcast(serviceIntent);

        Timber.i("DataLogger Started");

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Intent serviceIntent = new Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED);
        serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
        sendBroadcast(serviceIntent);
        instance = null;
        unregisterReceiver(mBluetoothUpdateReceiver);
        if (mLocationManager != null && PermissionsUtil.checkLocationPermission(this))
            mLocationManager.removeUpdates(locationListener);
        Timber.i("DataLogger stopped");
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /* Checks if external storage is available to at least read */
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private void updateFile() {
        if (logLocationData) {
            String longitude = "";
            String latitude = "";
            if (mLocation != null) {
                longitude = String.valueOf(mLocation.getLongitude());
                latitude = String.valueOf(mLocation.getLatitude());
            }
            FileUtil.writeLine(filename,
                    String.format(Locale.US, "%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%d",
                            sdf.format(new Date()),
                            latitude,
                            longitude,
                            WheelData.getInstance().getSpeedDouble(),
                            WheelData.getInstance().getVoltageDouble(),
                            WheelData.getInstance().getCurrentDouble(),
                            WheelData.getInstance().getPowerDouble(),
                            WheelData.getInstance().getBatteryLevel(),
                            WheelData.getInstance().getDistanceDouble(),
                            WheelData.getInstance().getTemperature()
                    ));
        } else {
            FileUtil.writeLine(filename,
                    String.format(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%d",
                            sdf.format(new Date()),
                            WheelData.getInstance().getSpeedDouble(),
                            WheelData.getInstance().getVoltageDouble(),
                            WheelData.getInstance().getCurrentDouble(),
                            WheelData.getInstance().getPowerDouble(),
                            WheelData.getInstance().getBatteryLevel(),
                            WheelData.getInstance().getDistanceDouble(),
                            WheelData.getInstance().getTemperature()
                    ));
        }
    }

    // Define a listener that responds to location updates
    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            // Called when a new location is found by the network location provider.
            mLocation = location;
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {}
    };

    @SuppressWarnings("MissingPermission")
    private Location getLastBestLocation() {

        Location locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        long GPSLocationTime = 0;
        if (null != locationGPS) { GPSLocationTime = locationGPS.getTime(); }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if ( 0 < GPSLocationTime - NetLocationTime ) {
            return locationGPS;
        }
        else {
            return locationNet;
        }
    }

    private void showToast(int message_id) {
        for (int i = 0; i <= 3; i++)
            Toast.makeText(this, message_id, Toast.LENGTH_LONG).show();
    }
}