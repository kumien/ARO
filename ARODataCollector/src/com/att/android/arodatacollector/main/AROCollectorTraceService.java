/*
 * Copyright 2012 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package com.att.android.arodatacollector.main;

import com.att.android.arodatacollector.utils.AROCollectorUtils;
import java.io.BufferedWriter;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.SocketException;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.ActivityManager;
import android.app.Service;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.location.GpsStatus;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * An ARO Data Collector service class that captures trace data from device
 * peripherals traces (like Wifi and GPS) during data collection.
 * 
 * */

public class AROCollectorTraceService extends Service {

	/** Log TAG string for ARO-Data Collector service class */
	private static final String TAG = "AROCollectorTraceService";

	/** Minimum SD card space required (2 MB) before start of the trace */
	private static final int AROSDCARD_MIN_SPACEKBYTES = 2048;

	/** Wifi trace file name */
	private static final String outWifiFileName = "wifi_events";

	/** battery trace file name */
	private static final String outBatteryFileName = "battery_events";

	/** Gps event trace file name */
	private static final String outGPSFileName = "gps_events";

	/** Radio event trace file name */
	private static final String outRadioFileName = "radio_events";

	/** camera event trace file name */
	private static final String outCameraFileName = "camera_events";

	/** Bluetooth event trace file name */
	private static final String outBluetoothFileName = "bluetooth_events";

	/** screen event trace file name */
	private static final String outScreenFileName = "screen_events";

	/** device ip address trace file name */
	private static final String outDeviceInfoFileName = "device_info";

	/** device ip address,make ,model trace file name */
	private static final String outDeviceDetailsFileName = "device_details";

	/** active running process trace file name */
	private static final String outActiveProcessFileName = "active_process";

	/** screen event trace file name */
	private static final String outScreenRotationFileName = "screen_rotations";

	/**
	 * LandScape Screen orientation
	 */
	private static final String LANDSCAPE_MODE = "landscape";
	/**
	 * LandScape Screen orientation
	 */
	private static final String PORTRAIT_MODE = "portrait";

	/**
	 * The boolean value to enable logs depending on if production build or
	 * debug build
	 */
	private static boolean mIsProduction = false;

	/**
	 * The boolean value to enable logs depending on if production build or
	 * debug build
	 */
	private static boolean DEBUG = !mIsProduction;

	/**
	 * Camera/GPS/Screen trace timer repeat time value to capture camera events
	 * ( 1/2 seconds)
	 */
	private static int HALF_SECOND_TARCE_TIMER_REPATE_TIME = 1000;

	/** Timer value to check SD Card space during trace cycle every 5 seconds */
	private static int SDCARD_TARCE_TIMER_REPATE_TIME = 5000;

	/** AROCollectorTraceService object */
	private static AROCollectorTraceService mDataCollectorService;

	/**
	 * The Application context of the ARo-Data Collector to gets and sets the
	 * application data
	 **/
	private ARODataCollector mApp;

	/** Broadcast receiver for Batter events */
	private BroadcastReceiver mBatteryLevelReceiver;

	/** Active running processes package list */
	private List<RunningAppProcessInfo> mActiveProcessprocess;

	/** GPS active boolean flag */
	private Boolean mGPSActive = false;

	/** Current Camera state on/off boolean flag */
	private Boolean mCameraOn = false;

	/** Previous Camera state on/off boolean flag */
	private Boolean mPrevCameraOn = true;

	/** Screen brightness value from 0-255 */
	private float mScreencurBrightness = 0;

	/** Previous Screen brightness value */
	private float mPrevScreencurBrightness = 1;

	/** Screen timeout (Device sleep) value in seconds */
	private int mScreenTimeout = 0;

	/** Previous Screen timeout (Device sleep) value in seconds */
	private int mPrevScreenTimeout = 0;

	/** Location Manager class object */
	private LocationManager mGPSStatesManager;

	/** GPS State listener */
	private GpsStatus.Listener mGPSStatesListner;

	/** Previous GPS enabled state */
	private boolean prevGpsEnabledState = false;

	/** Timer to run every 500 milliseconds to check GPS states */
	private Timer checkLocationService = new Timer();

	/** Timer to run every 500 milliseconds to check Camera states */
	private Timer checkCameraLaunch = new Timer();

	/**
	 * Timer to run every 5 seconds to check SD card space is always greater
	 * than 5MB to continue trace
	 */
	private Timer checkSDCardSpace = new Timer();

	/**
	 * Timer to run every 500 milliseconds to get screen brightness value in
	 * order to get change
	 */
	private Timer checkScreenBrightness = new Timer();

	/** Intent filter to adding action for broadcast receivers **/
	private IntentFilter mAROIntentFilter;

	/** Intent filter to adding action for bluetooth broadcast receivers **/
	private IntentFilter mAROBluetoothIntentFilter;

	/** Telephony manager class object **/
	private TelephonyManager mTelphoneManager;

	/** Phone state listener listener to get RSSI value **/
	private PhoneStateListener mPhoneStateListener;
	private ConnectivityManager mConnectivityManager;
	private WifiManager mWifiManager;
	private String mWifiMacAddress;
	private String mWifiNetworkSSID;
	private int mWifiRssi;
	private boolean isFirstBearerChange = true;

	/** Output stream and Buffer Writer for peripherals traces files */
	private OutputStream mWifiTraceOutputFile;
	private BufferedWriter mWifiTracewriter;
	private OutputStream mBatteryTraceOutputFile;
	private BufferedWriter mBatteryTracewriter;
	private OutputStream mGPSTraceOutputFile;
	private BufferedWriter mGPSTracewriter;
	private OutputStream mRadioTraceOutputFile;
	private BufferedWriter mRadioTracewriter;
	private OutputStream mCameraTraceOutputFile;
	private BufferedWriter mCameraTracewriter;
	private OutputStream mBluetoohTraceOutputFile;
	private BufferedWriter mBluetoothTracewriter;
	private OutputStream mScreenOutputFile;
	private BufferedWriter mScreenTracewriter;
	private OutputStream mScreenRotationOutputFile;
	private BufferedWriter mScreenRotationTracewriter;
	private OutputStream mActiveProcessOutputFile;
	private BufferedWriter mActiveProcessTracewriter;
	private OutputStream mDeviceInfoOutputFile;
	private OutputStream mDeviceDetailsOutputFile;
	private BufferedWriter mDeviceInfoWriter;
	private BufferedWriter mDeviceDetailsWriter;

	/** ARO Data Collector utilities class object */
	private AROCollectorUtils mAroUtils;

	/** The broadcast receiver that listens for screen rotation. */
	private BroadcastReceiver mScreenRotationReceiver;

	/** String constants used in ARO trace files */
	private static class AroTraceFileConstants {
		static final String OFF = "OFF";
		static final String ON = "ON";
		static final String CONNECTED = "CONNECTED";
		static final String DISCONNCTED = "DISCONNECTED";
		static final String CONNECTED_NETWORK = "CONNECTED";
		static final String DISCONNECTING_NETWORK = "DISCONNECTING";
		static final String CONNECTING_NETWORK = "CONNECTING";
		static final String DISCONNECTED_NETWORK = "DISCONNECTED";
		static final String SUSPENDED_NETWORK = "SUSPENDED";
		static final String UNKNOWN_NETWORK = "UNKNOWN";
		static String IMPORTANCE_BACKGROUND = "Background";
		static String IMPORTANCE_FOREGROUND = "Foreground";
	}

	/**
	 * Handles processing when an AROCollectorTraceService object is created.
	 * Overrides the android.app.Service#onCreate method.
	 * 
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		mDataCollectorService = this;
		mApp = (ARODataCollector) getApplication();
		mAroUtils = new AROCollectorUtils();
		startARODataTraceCollection();

	}

	/**
	 * Handles processing when an AROCollectorTraceService object is destroyed.
	 * Overrides the android.app.Service#onDestroy method.
	 * 
	 * @see android.app.Service#onDestroy()
	 */
	@Override
	public void onDestroy() {
		super.onDestroy();
		stopARODataTraceCollection();
		mApp.cancleAROAlertNotification();
	}

	/**
	 * Returns a valid instance of the AROCollectorTraceService class.
	 * 
	 * @return An AROCollectorTraceService object.
	 */
	public static AROCollectorTraceService getServiceObj() {
		return mDataCollectorService;
	}

	/**
	 * Initializes and starts the ARO-Data Collector peripherals trace
	 * collection
	 * 
	 * @throws FileNotFoundException
	 */
	private void startARODataTraceCollection() {
		try {
			initAROTraceFile();
			startAROScreenTraceMonitor();
			startAROGpsTraceMonitor();
			startAROBatteryLevelMonitor();
			startARORadioTraceMonitor();
			startAROBluetoothTraceMonitor();
			startAROWifiTraceMonitor();
			startAROActiveProcessTrace();
			startCameraTrace();
			startARODataBearerChangeNotification();
			startARODeviceSDCardSpaceMidTrace();
			startAroScreenRotationMonitor();
		} catch (FileNotFoundException e) {
			Log.e(TAG,
					"exception in initAROTraceFile: Failed to start ARO-Data Collector Trace",
					e);
		}

	}

	/**
	 * Captures the device information
	 * 
	 */
	private void captureDeviceInfo() {
		final String ipAddress;
		final String deviceModel = Build.MODEL;
		final String deviceMake = Build.MANUFACTURER;
		final String osVersion = Build.VERSION.RELEASE;
		final String appVersion = mApp.getVersion();

		try {
			ipAddress = mAroUtils.getLocalIpAddress();
			if (ipAddress != null) {
				writeTraceLineToAROTraceFile(mDeviceInfoWriter, ipAddress,
						false);
			}
		} catch (SocketException e) {
			Log.e(TAG, "exception in getLocalIpAddress", e);
		}
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter,
				getApplicationContext().getPackageName(), false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter, deviceModel, false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter, deviceMake, false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter, "android", false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter, osVersion, false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter, appVersion, false);
		writeTraceLineToAROTraceFile(mDeviceDetailsWriter,
				Integer.toString(getDeviceNetworkType()), false);

	}

	/**
	 * Stops the ARO-Data Collector peripherals trace collection
	 * 
	 */
	private void stopARODataTraceCollection() {
		stopAROScreenTraceMonitor();
		stopAROGpsTraceMonitor();
		stopAROBatteryLevelMonitor();
		stopARORadioTraceMonitor();
		stopAROBluetoothTraceMonitor();
		stopAROWifiTraceMonitor();
		stopCameraTrace();
		stopARODataBearerChangeNotification();
		stopAroScreenRotationMonitor();
		try {
			closeAROTraceFile();
		} catch (IOException e) {
			Log.e(TAG, "exception in closeAROTraceFile", e);
		}
		stopARODeviceSDCardSpaceMidTrace();

	}

	/**
	 * Gets the current connected data network type of device i.e 3G/LTE/Wifi
	 * 
	 * @return mCellNetworkType Current network type
	 */
	private int getDeviceNetworkType() {

		final TelephonyManager mAROtelManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		int networkType = mAROtelManager.getNetworkType();
		// Check if the current network is WiFi
		if (mApp.getCurrentNetworkType() == 1) {
			networkType = -1;
		}
		return networkType;
	}

	/**
	 * Method will initialize all the trace files Streams and open it for
	 * writing (i.e wifi/ Baterry/Gps trace files etc)
	 * 
	 * @throws FileNotFoundException
	 */
	private void initAROTraceFile() throws FileNotFoundException {

		final String mAroTraceDatapath = mApp.getTcpDumpTraceFolderName();
		if (DEBUG) {
			Log.d(TAG, "mAroTraceDatapath=" + mAroTraceDatapath);
		}
		mWifiTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outWifiFileName);
		mWifiTracewriter = new BufferedWriter(new OutputStreamWriter(
				mWifiTraceOutputFile));
		mRadioTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outRadioFileName);
		mRadioTracewriter = new BufferedWriter(new OutputStreamWriter(
				mRadioTraceOutputFile));
		mCameraTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outCameraFileName);
		mCameraTracewriter = new BufferedWriter(new OutputStreamWriter(
				mCameraTraceOutputFile));
		mBatteryTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outBatteryFileName);
		mBatteryTracewriter = new BufferedWriter(new OutputStreamWriter(
				mBatteryTraceOutputFile));
		mGPSTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outGPSFileName);
		mGPSTracewriter = new BufferedWriter(new OutputStreamWriter(
				mGPSTraceOutputFile));
		mScreenOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outScreenFileName);
		mScreenRotationOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outScreenRotationFileName);
		mScreenTracewriter = new BufferedWriter(new OutputStreamWriter(
				mScreenOutputFile));
		mScreenRotationTracewriter = new BufferedWriter(new OutputStreamWriter(
				mScreenRotationOutputFile));
		mActiveProcessOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outActiveProcessFileName);
		mActiveProcessTracewriter = new BufferedWriter(new OutputStreamWriter(
				mActiveProcessOutputFile));
		mBluetoohTraceOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outBluetoothFileName);
		mBluetoothTracewriter = new BufferedWriter(new OutputStreamWriter(
				mBluetoohTraceOutputFile));
		mDeviceInfoOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outDeviceInfoFileName);
		mDeviceDetailsOutputFile = new FileOutputStream(mAroTraceDatapath
				+ outDeviceDetailsFileName);
		mDeviceInfoWriter = new BufferedWriter(new OutputStreamWriter(
				mDeviceInfoOutputFile));
		mDeviceDetailsWriter = new BufferedWriter(new OutputStreamWriter(
				mDeviceDetailsOutputFile));

	}

	/**
	 * Method will close all trace file Streams and closes the files
	 * 
	 * @throws IOException
	 */
	private void closeAROTraceFile() throws IOException {
		mWifiTracewriter.close();
		mWifiTraceOutputFile.flush();
		mWifiTraceOutputFile.close();
		mRadioTracewriter.close();
		mRadioTraceOutputFile.flush();
		mRadioTraceOutputFile.close();
		mCameraTracewriter.close();
		mCameraTraceOutputFile.flush();
		mCameraTraceOutputFile.close();
		mBluetoothTracewriter.close();
		mBluetoohTraceOutputFile.flush();
		mBluetoohTraceOutputFile.close();
		mBatteryTracewriter.close();
		mBatteryTraceOutputFile.flush();
		mBatteryTraceOutputFile.close();
		mGPSTracewriter.close();
		mGPSTraceOutputFile.flush();
		mGPSTraceOutputFile.close();
		mScreenTracewriter.close();
		mScreenOutputFile.flush();
		mScreenOutputFile.close();
		mScreenRotationTracewriter.close();
		mScreenRotationOutputFile.flush();
		mScreenRotationOutputFile.close();
		mActiveProcessTracewriter.close();
		mActiveProcessOutputFile.flush();
		mActiveProcessOutputFile.close();
		mDeviceInfoWriter.close();
		mDeviceInfoOutputFile.flush();
		mDeviceInfoOutputFile.close();
		mDeviceDetailsWriter.close();
		mDeviceDetailsOutputFile.flush();
		mDeviceDetailsOutputFile.close();

	}

	/**
	 * Notify the SD card error during trace collection and displays the error
	 * dialog by calling Main Activity
	 */
	private void aroSDCardErrorUIUpdate() {
		if (AROCollectorService.getServiceObj() != null) {
			mApp.setMediaMountedMidAROTrace(mAroUtils.checkSDCardMounted());
			AROCollectorService.getServiceObj().requestDataCollectorStop();
		}
	}

	/**
	 * Method write given String message to trace file passed as an argument
	 * outputfilewriter : Name of Trace File writer to which trace has to be
	 * written content : Trace message to be written
	 */
	private void writeTraceLineToAROTraceFile(BufferedWriter outputfilewriter,
			String content, boolean timestamp) {
		try {
			final String eol = System.getProperty("line.separator");
			if (timestamp) {
				outputfilewriter
						.write(mAroUtils.getDataCollectorEventTimeStamp() + " "
								+ content + eol);
			} else {
				outputfilewriter.write(content + eol);
			}
		} catch (IOException e) {
			// TODO: Need to display the exception error instead of Mid Trace
			// mounted error
			mApp.setMediaMountedMidAROTrace(mAroUtils.checkSDCardMounted());
			if (DEBUG) {
				Log.i(TAG, "Exception in writeTraceLineToAROTraceFile");
				Log.e(TAG, "exception in writeTraceLineToAROTraceFile", e);
			}
		}
	}

	/**
	 * Checks the device SD Card space every seconds if its less than 1 MB to
	 * write traces
	 */
	private void startARODeviceSDCardSpaceMidTrace() {

		checkSDCardSpace.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				if (mAroUtils.checkSDCardMemoryAvailable() < AROSDCARD_MIN_SPACEKBYTES) {
					aroSDCardErrorUIUpdate();
					if (DEBUG) {
						Log.i(TAG, "startARODeviceSDCardSpaceMidTrace="
								+ mAroUtils.checkSDCardMemoryAvailable());
					}
					return;
				}
			}
		}, SDCARD_TARCE_TIMER_REPATE_TIME, SDCARD_TARCE_TIMER_REPATE_TIME);

	}

	/**
	 * Stops the SD Card memory check timer during the trace
	 */
	private void stopARODeviceSDCardSpaceMidTrace() {
		checkSDCardSpace.cancel();
		checkSDCardSpace = null;

	}

	/**
	 * Starts the camera trace collection
	 */
	private void startCameraTrace() {
		checkCameraLaunch.scheduleAtFixedRate(
				new TimerTask() {
					public void run() {
						final String recentTaskName = getRecentTaskInfo()
								.toLowerCase();
						if (recentTaskName.contains("camera")
								|| checkCurrentProcessStateForGround("camera")) {
							mCameraOn = true;
						} else
							mCameraOn = false;
						if (checkCurrentProcessState("camera"))
							mCameraOn = false;
						if (mCameraOn && !mPrevCameraOn) {
							if (DEBUG)
								Log.i(TAG, "Camera Turned on");
							writeTraceLineToAROTraceFile(mCameraTracewriter,
									"ON", true);
							mCameraOn = true;
							mPrevCameraOn = true;
						} else if (!mCameraOn && mPrevCameraOn) {
							if (DEBUG)
								Log.i(TAG, "Camera Turned Off");
							writeTraceLineToAROTraceFile(mCameraTracewriter,
									"OFF", true);
							mCameraOn = false;
							mPrevCameraOn = false;
						}
					}
				}, HALF_SECOND_TARCE_TIMER_REPATE_TIME,
				HALF_SECOND_TARCE_TIMER_REPATE_TIME);
	}

	/**
	 * Stops the camera trace collection
	 */
	private void stopCameraTrace() {
		checkCameraLaunch.cancel();
		checkCameraLaunch = null;
	}

	/**
	 * Screen trace data broadcast receiver
	 */
	private BroadcastReceiver mAROScreenTraceReceiver = new BroadcastReceiver() {

		// Screen state on-off boolean flag
		Boolean mScreenOn = false;

		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();

			if (action.equals(Intent.ACTION_SCREEN_OFF)) {
				mScreenOn = false;
			} else if (action.equals(Intent.ACTION_SCREEN_ON)) {
				mScreenOn = true;
			}
			getScreenBrigthnessTimeout();
			if (mScreenOn) {
				writeTraceLineToAROTraceFile(mScreenTracewriter,
						AroTraceFileConstants.ON + " " + mScreenTimeout + " "
								+ mScreencurBrightness, true);
				mPrevScreencurBrightness = mScreencurBrightness;
				mPrevScreenTimeout = mScreenTimeout;
			} else {
				writeTraceLineToAROTraceFile(mScreenTracewriter,
						AroTraceFileConstants.OFF, true);
				mPrevScreencurBrightness = mScreencurBrightness;
				mPrevScreenTimeout = mScreenTimeout;
			}

			if (DEBUG) {
				Log.d(TAG, "Screen brightness: " + mScreencurBrightness);
				Log.d(TAG, "Screen Timeout: " + mScreenTimeout);
			}

		}
	};

	/**
	 * Gets the screen brightness and timeout value from Settings file
	 * 
	 * @throws SettingNotFoundException
	 */
	private void getScreenBrigthnessTimeout() {
		try {
			mScreencurBrightness = Settings.System.getInt(getContentResolver(),
					Settings.System.SCREEN_BRIGHTNESS);
			if (mScreencurBrightness >= 255)
				mScreencurBrightness = 240;
			// Brightness Min value 15 and Max 255
			mScreencurBrightness = Math
					.round((mScreencurBrightness / 240) * 100);
			mScreenTimeout = Settings.System.getInt(getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT);
			mScreenTimeout = mScreenTimeout / 1000; // In Seconds
		} catch (SettingNotFoundException e) {
			Log.e(TAG, "exception in getScreenBrigthnessTimeout", e);
		}

	}

	/**
	 * Checks for the data connection bearer change during the life time of
	 * trace collection
	 */
	private BroadcastReceiver mAROBearerChangeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
				final NetworkInfo mAROActiveNetworkInfo = (NetworkInfo) intent
						.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
				final ConnectivityManager mAROConnectiviyMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
				if (!isFirstBearerChange) {
					boolean activeDataConnection = true;
					if (mAROConnectiviyMgr.getNetworkInfo(0).getState() == NetworkInfo.State.DISCONNECTED) {
						activeDataConnection = false;
					}
					// !mAROActiveNetworkInfo.isConnected() As this is causing
					// issue to abnormal termination of trace mid way
					if (mAROActiveNetworkInfo.getType() != mApp
							.getCurrentNetworkType()
							&& mAROActiveNetworkInfo.getType() <= 1
							|| !activeDataConnection) { // To Do: The Bearer
														// change has been only
														// done for Wifi/Mobile
														// network.
						mApp.setDataCollectorBearerChange(true);
						AROCollectorService.getServiceObj()
								.requestDataCollectorStop();
						if (DEBUG) {
							Log.i(TAG,
									"Bearer Change TRUE" + "Current="
											+ mAROActiveNetworkInfo.getType()
											+ "Previous="
											+ mApp.getCurrentNetworkType());
							Log.i(TAG, "Network Connected" + "isConnected="
									+ mAROActiveNetworkInfo.isConnected());
						}
					}
				} else if (isFirstBearerChange) {
					mApp.setCurrentNetworkType(mAROActiveNetworkInfo);
				}
				isFirstBearerChange = false;
			}
			// device_details trace file
			captureDeviceInfo();
		}
	};

	/**
	 * Wifi trace data broadcast receiver
	 */
	private BroadcastReceiver mAROWifiTraceReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
				if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.DISCONNECTED_NETWORK, true);
				} else if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.OFF, true);
				}
			}
			if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent
					.getAction())) {

				final NetworkInfo info = (NetworkInfo) intent
						.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				final NetworkInfo.State state = info.getState();

				switch (state) {

				case CONNECTING:
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.CONNECTING_NETWORK, true);
					break;
				case CONNECTED:
					collectWifiNetworkData();
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.CONNECTED_NETWORK + " "
									+ mWifiMacAddress + " " + mWifiRssi + " "
									+ mWifiNetworkSSID, true);
					break;
				case DISCONNECTING:
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.DISCONNECTING_NETWORK, true);
					break;
				case DISCONNECTED:
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.DISCONNECTED_NETWORK, true);
					break;
				case SUSPENDED:
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.SUSPENDED_NETWORK, true);
					break;
				case UNKNOWN:
					writeTraceLineToAROTraceFile(mWifiTracewriter,
							AroTraceFileConstants.UNKNOWN_NETWORK, true);
					break;
				}
			}

		}//

	};

	/**
	 * Bluetooth trace data broadcast receiver
	 */
	private BroadcastReceiver mAROBluetoothTraceReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {

				switch (BluetoothAdapter.getDefaultAdapter().getState()) {
				case BluetoothAdapter.STATE_ON:
					writeTraceLineToAROTraceFile(mBluetoothTracewriter,
							AroTraceFileConstants.DISCONNCTED, true);
					break;

				case BluetoothAdapter.STATE_OFF:
					writeTraceLineToAROTraceFile(mBluetoothTracewriter,
							AroTraceFileConstants.OFF, true);
					break;
				}
			}
			if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
					|| BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
					|| BluetoothDevice.ACTION_FOUND.equals(action)) {

				final BluetoothDevice device = intent
						.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					writeTraceLineToAROTraceFile(mBluetoothTracewriter,
							AroTraceFileConstants.DISCONNCTED, true);
				} else if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
					writeTraceLineToAROTraceFile(mBluetoothTracewriter,
							AroTraceFileConstants.CONNECTED, true);
				}
			}
		}
	};

	/**
	 * Capture the device radio RSSI(signal strength) during the trace
	 * 
	 */
	private void setARORadioSignalListener() {
		mPhoneStateListener = new PhoneStateListener() {
			public void onSignalStrengthsChanged(SignalStrength signalStrength) {
				super.onSignalStrengthsChanged(signalStrength);

				// GSM Radio signal strength in integer value which will be
				// converted to dDm (This is default considered network type)
				String mRadioSignalStrength = String.valueOf(0);
				TelephonyManager mTelphoneManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
				if (signalStrength.isGsm() || mTelphoneManager.getNetworkType() == 13) {
					
				    int mLteSignalStrength = 0;
					int mLteRsrp = 0;
				    int mLteRsrq = 0;
				    int mLteRssnr = 0;
				    int mLteCqi = 0;
					if (mTelphoneManager.getNetworkType() == 13) {
						try {
							mLteSignalStrength = Integer.parseInt(mAroUtils.getSpecifiedFieldValues(
									SignalStrength.class, signalStrength, "mLteSignalStrength"));
						} catch (NumberFormatException nmb) {
							Log.e(TAG,
									"mLteSignalStrength not found in LTE Signal Strength");
						}

						try {
							mLteRsrp = Integer.parseInt(mAroUtils.getSpecifiedFieldValues(
									SignalStrength.class, signalStrength, "mLteRsrp"));
						} catch (NumberFormatException nmb) {
							Log.e(TAG,
									"mLteRsrp not found in LTE Signal Strength");
						}

						try {
							mLteRsrq = Integer.parseInt(mAroUtils.getSpecifiedFieldValues(
									SignalStrength.class, signalStrength, "mLteRsrq"));
						} catch (NumberFormatException nmb) {
							Log.e(TAG,
									"mLteRsrq not found in LTE Signal Strength");
						}
						try {
							mLteRssnr = Integer.parseInt(mAroUtils.getSpecifiedFieldValues(
									SignalStrength.class, signalStrength, "mLteRssnr"));
						} catch (NumberFormatException nmb) {
							Log.e(TAG,
									"mLteRssnr not found in LTE Signal Strength");
						}
						try {
							mLteCqi = Integer.parseInt(mAroUtils.getSpecifiedFieldValues(
									SignalStrength.class, signalStrength, "mLteCqi"));
						} catch (NumberFormatException nmb) {
							Log.e(TAG,
									"mLteCqi not found in LTE Signal Strength");
						}

					}

					// Check to see if LTE parameters are set
					if ((mLteSignalStrength == 0 && mLteRsrp == 0 && mLteRsrq == 0 && mLteCqi == 0)
							|| (mLteSignalStrength == -1 && mLteRsrp == -1 && mLteRsrq == -1 && mLteCqi == -1)) {
						
						// No LTE parameters set.  Use GSM signal strength
						int gsmSignalStrength = signalStrength.getGsmSignalStrength();
						if (signalStrength.isGsm() && gsmSignalStrength != 99) {
							mRadioSignalStrength = String.valueOf(-113
									+ (gsmSignalStrength * 2));
						}
					} else {
						
						// If hidden LTE parameters were defined and not set to default values, then used them
						mRadioSignalStrength = mLteSignalStrength + " "
								+ mLteRsrp + " " + mLteRsrq + " " + mLteRssnr
								+ " " + mLteCqi;
					}
				}
				/**
				 * If the network type is CDMA then look for CDMA signal
				 * strength values.
				 */
				else if ((mTelphoneManager.getNetworkType() == TelephonyManager.NETWORK_TYPE_CDMA)) {

					mRadioSignalStrength = String.valueOf(signalStrength.getCdmaDbm());
				}
				/**
				 * If the network type is EVDO O/A then look for EVDO signal
				 * strength values.
				 */
				else if (mTelphoneManager.getNetworkType() == TelephonyManager.NETWORK_TYPE_EVDO_0
						|| mTelphoneManager.getNetworkType() == TelephonyManager.NETWORK_TYPE_EVDO_A) {

					mRadioSignalStrength = String.valueOf(signalStrength.getEvdoDbm());
				}

				if (DEBUG) {
					Log.i(TAG, "signal strength changed to "+ mRadioSignalStrength);
				}
				writeTraceLineToAROTraceFile(mRadioTracewriter,
						mRadioSignalStrength, true);
			}

		};
	}

	/**
	 * Captures the GPS trace data during the trace cycle
	 * 
	 */
	private class GPSStatesListener implements GpsStatus.Listener {

		@Override
		public void onGpsStatusChanged(int event) {

			switch (event) {
			case GpsStatus.GPS_EVENT_STARTED:
				writeTraceLineToAROTraceFile(mGPSTracewriter, "ACTIVE", true);
				mGPSActive = true;
				break;
			case GpsStatus.GPS_EVENT_STOPPED:
				writeTraceLineToAROTraceFile(mGPSTracewriter, "STANDBY", true);
				mGPSActive = false;
				break;
			}
		}
	}

	/**
	 * Checks if the GPS radio is turned on and receiving fix
	 * 
	 * @return boolean value to represent if the location service is enabled or
	 *         not
	 */
	private boolean isLocationServiceEnabled() {
		boolean enabled = false;
		// first, make sure at least one provider actually exists
		final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
		final boolean gpsExists = (lm.getProvider(LocationManager.GPS_PROVIDER) != null);
		final boolean networkExists = (lm
				.getProvider(LocationManager.NETWORK_PROVIDER) != null);
		if (gpsExists || networkExists) {
			enabled = ((!gpsExists || lm
					.isProviderEnabled(LocationManager.GPS_PROVIDER)) && (!networkExists || lm
					.isProviderEnabled(LocationManager.NETWORK_PROVIDER)));
		}
		return enabled;
	}

	/**
	 * Starts the GPS peripherals trace collection
	 */
	private void startAROGpsTraceMonitor() {
		mGPSStatesManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		mGPSStatesListner = new GPSStatesListener();
		mGPSStatesManager.addGpsStatusListener(mGPSStatesListner);
		checkLocationService.scheduleAtFixedRate(
				new TimerTask() {
					public void run() {
						// Current GPS enabled state
						final boolean currentGpsEnabledState = isLocationServiceEnabled();
						if (currentGpsEnabledState != prevGpsEnabledState) {
							if (currentGpsEnabledState) {
								if (DEBUG) {
									Log.d(TAG, "gps enabled: ");
								}
								if (!mGPSActive)
									writeTraceLineToAROTraceFile(
											mGPSTracewriter, "STANDBY", true);
							} else {
								if (DEBUG) {
									Log.d(TAG, "gps Disabled: ");
								}
								writeTraceLineToAROTraceFile(mGPSTracewriter,
										"OFF", true);
							}
						}
						prevGpsEnabledState = currentGpsEnabledState;
					}
				}, HALF_SECOND_TARCE_TIMER_REPATE_TIME,
				HALF_SECOND_TARCE_TIMER_REPATE_TIME);
	}

	/**
	 * Stop the GPS peripherals trace collection
	 */
	private void stopAROGpsTraceMonitor() {
		mGPSStatesManager.removeGpsStatusListener(mGPSStatesListner);
		mGPSStatesManager = null;
		checkLocationService.cancel();
	}

	/**
	 * Starts the device radio trace collection
	 */
	private void startARORadioTraceMonitor() {
		mTelphoneManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		setARORadioSignalListener();
		mTelphoneManager.listen(mPhoneStateListener,
				PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
						| PhoneStateListener.LISTEN_CALL_STATE);
	}

	/**
	 * Stops the device radio trace collection
	 */
	private void stopARORadioTraceMonitor() {
		mTelphoneManager.listen(mPhoneStateListener,
				PhoneStateListener.LISTEN_NONE);
		mTelphoneManager = null;
		mPhoneStateListener = null;

	}

	/**
	 * Starts the device screen trace collection
	 */
	private void startAROScreenTraceMonitor() {
		mAROIntentFilter = new IntentFilter();
		mAROIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
		mAROIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
		checkScreenBrightness.scheduleAtFixedRate(
				new TimerTask() {
					public void run() {
						getScreenBrigthnessTimeout();
						if ((mScreencurBrightness != mPrevScreencurBrightness)
								|| (mScreenTimeout != mPrevScreenTimeout)) {
							writeTraceLineToAROTraceFile(mScreenTracewriter,
									AroTraceFileConstants.ON + " "
											+ mScreenTimeout + " "
											+ mScreencurBrightness, true);
							mPrevScreencurBrightness = mScreencurBrightness;
							mPrevScreenTimeout = mScreenTimeout;

						}
					}
				}, HALF_SECOND_TARCE_TIMER_REPATE_TIME,
				HALF_SECOND_TARCE_TIMER_REPATE_TIME);
		registerReceiver(mAROScreenTraceReceiver, mAROIntentFilter);
	}

	/**
	 * Stop the device screen trace collection
	 */
	private void stopAROScreenTraceMonitor() {
		unregisterReceiver(mAROScreenTraceReceiver);
		checkScreenBrightness.cancel();
		checkScreenBrightness = null;
		mAROIntentFilter = null;
	}

	/**
	 * Starts the bluetooth peripherals trace collection
	 */
	private void startAROBluetoothTraceMonitor() {
		switch (BluetoothAdapter.getDefaultAdapter().getState()) {
		case BluetoothAdapter.STATE_ON:
			if (BluetoothAdapter.getDefaultAdapter().getBondedDevices()
					.isEmpty()) {
				writeTraceLineToAROTraceFile(mBluetoothTracewriter,
						AroTraceFileConstants.DISCONNCTED, true);
			} else {
				writeTraceLineToAROTraceFile(mBluetoothTracewriter,
						AroTraceFileConstants.CONNECTED, true);
			}
			break;

		case BluetoothAdapter.STATE_OFF:
			writeTraceLineToAROTraceFile(mBluetoothTracewriter,
					AroTraceFileConstants.OFF, true);
			break;
		}

		mAROBluetoothIntentFilter = new IntentFilter();
		mAROBluetoothIntentFilter
				.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		mAROBluetoothIntentFilter
				.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
		mAROBluetoothIntentFilter
				.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		mAROBluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
		registerReceiver(mAROBluetoothTraceReceiver, mAROBluetoothIntentFilter);

	}

	/**
	 * Stops the bluetooth peripherals trace collection
	 */
	private void stopAROBluetoothTraceMonitor() {
		unregisterReceiver(mAROBluetoothTraceReceiver);
		mAROBluetoothIntentFilter = null;
	}

	/**
	 * Gets the current connected bearer
	 * 
	 * @return boolean value to validate if current bearer is wifi
	 */
	private Boolean getifCurrentBearerWifi() {
		int type = 0;
		if (mConnectivityManager == null)
			return false;
		if (mConnectivityManager.getActiveNetworkInfo() != null) {
			type = mConnectivityManager.getActiveNetworkInfo().getType();
		}
		if (type == ConnectivityManager.TYPE_MOBILE) {
			if (DEBUG) {
				Log.i(TAG, " Connection Type :  Mobile");
			}
			return false;
		} else {
			if (DEBUG) {
				Log.i(TAG, " Connection Type :  Wifi");
			}
			return true;
		}
	}

	/**
	 * Collects the wifi network trace data
	 */
	private void collectWifiNetworkData() {
		// Get WiFi status
		if (mWifiManager != null && getifCurrentBearerWifi()) {
			mWifiMacAddress = mWifiManager.getConnectionInfo().getBSSID();
			mWifiNetworkSSID = mWifiManager.getConnectionInfo().getSSID();
			mWifiRssi = mWifiManager.getConnectionInfo().getRssi();
		}
	}

	/**
	 * Starts the bearer change notification broadcast
	 */
	private void startARODataBearerChangeNotification() {
		mAROIntentFilter = new IntentFilter();
		mAROIntentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		registerReceiver(mAROBearerChangeReceiver, mAROIntentFilter);
	}

	/**
	 * Stops the bearer change notification broadcast
	 */
	private void stopARODataBearerChangeNotification() {
		unregisterReceiver(mAROBearerChangeReceiver);
	}

	/**
	 * Starts the wifi trace collection
	 */
	private void startAROWifiTraceMonitor() {
		IntentFilter mAROWifiIntentFilter;
		// Setup WiFi
		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		mAROWifiIntentFilter = new IntentFilter();
		mAROWifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		mAROWifiIntentFilter
				.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
		mAROWifiIntentFilter
				.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
		mAROWifiIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
		mAROWifiIntentFilter
				.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
		registerReceiver(mAROWifiTraceReceiver, mAROWifiIntentFilter);

	}

	/**
	 * Stops the wifi trace collection
	 */
	private void stopAROWifiTraceMonitor() {
		unregisterReceiver(mAROWifiTraceReceiver);
		mWifiManager = null;
		mConnectivityManager = null;
	}

	/**
	 * Gets the recent opened package name
	 * 
	 * @return recent launched package name
	 */
	private String getRecentTaskInfo() {
		/** Package name of recent launched application */
		String mLastLaucnhedProcess = " ";
		final ActivityManager mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		final List<?> l = mActivityManager.getRecentTasks(5,
				ActivityManager.RECENT_WITH_EXCLUDED);
		final RecentTaskInfo rti = (RecentTaskInfo) l.get(0);
		if (!mLastLaucnhedProcess.equalsIgnoreCase(rti.baseIntent
				.getComponent().getPackageName())
				&& !rti.baseIntent
						.getComponent()
						.getPackageName()
						.equalsIgnoreCase(
								"com.att.android.arodatacollector.main")) {
			if (DEBUG)
				Log.i(TAG, "New Task="+ rti.baseIntent.getComponent().getPackageName());
			mLastLaucnhedProcess = rti.baseIntent.getComponent()
					.getPackageName();
			return mLastLaucnhedProcess;
		}
		mLastLaucnhedProcess = rti.baseIntent.getComponent().getPackageName();
		return mLastLaucnhedProcess;

	}

	/**
	 * Starts the active process trace by logging all running process in the
	 * trace file
	 */
	private void startAROActiveProcessTrace() {
		// mActiveProcessStates //
		String[] mActiveProcessStates;
		final ActivityManager mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		mActiveProcessprocess = mActivityManager.getRunningAppProcesses();
		mActiveProcessStates = new String[mActiveProcessprocess.size()];
		for (final Iterator<RunningAppProcessInfo> iterator = mActiveProcessprocess
				.iterator(); iterator.hasNext();) {
			final RunningAppProcessInfo runningAppProcessInfo = (RunningAppProcessInfo) iterator
					.next();
			final int pImportance = runningAppProcessInfo.importance;
			int Index = 0;
			switch (pImportance) {

			case RunningAppProcessInfo.IMPORTANCE_BACKGROUND:
				mActiveProcessStates[Index] = "Name:"
						+ runningAppProcessInfo.processName + " State:"
						+ AroTraceFileConstants.IMPORTANCE_BACKGROUND;
				writeTraceLineToAROTraceFile(mActiveProcessTracewriter,
						mActiveProcessStates[Index], true);
				Index++;
				break;

			case RunningAppProcessInfo.IMPORTANCE_FOREGROUND:
				mActiveProcessStates[Index] = "Name:"
						+ runningAppProcessInfo.processName + " State:"
						+ AroTraceFileConstants.IMPORTANCE_FOREGROUND;
				writeTraceLineToAROTraceFile(mActiveProcessTracewriter,
						mActiveProcessStates[Index], true);
				Index++;
				break;
			}
		}
	}

	/**
	 * Checks the state of process is background
	 * 
	 * @param process
	 * 
	 *            name
	 * @return boolean value to represent the if package state is background
	 */
	private boolean checkCurrentProcessState(String processname) {
		final ActivityManager mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		mActiveProcessprocess = mActivityManager.getRunningAppProcesses();
		for (final Iterator<RunningAppProcessInfo> iterator = mActiveProcessprocess
				.iterator(); iterator.hasNext();) {
			final RunningAppProcessInfo runningAppProcessInfo = (RunningAppProcessInfo) iterator
					.next();
			final String pSname = runningAppProcessInfo.processName
					.toLowerCase();
			final int pImportance = runningAppProcessInfo.importance;
			if (pSname.contains(processname.toLowerCase()) && !pSname.contains(":")) {
				switch (pImportance) {
				case RunningAppProcessInfo.IMPORTANCE_BACKGROUND:
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks the state of process is foreground
	 * 
	 * @param process
	 *            name
	 * 
	 * @return boolean value to represent the if package state is foreground
	 */
	private boolean checkCurrentProcessStateForGround(String processname) {
		final ActivityManager mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		mActiveProcessprocess = mActivityManager.getRunningAppProcesses();
		for (final Iterator<RunningAppProcessInfo> iterator = mActiveProcessprocess
				.iterator(); iterator.hasNext();) {
			final RunningAppProcessInfo runningAppProcessInfo = (RunningAppProcessInfo) iterator
					.next();
			final String pSname = runningAppProcessInfo.processName
					.toLowerCase();
			final int pImportance = runningAppProcessInfo.importance;
			if (pSname.contains(processname.toLowerCase())) {
				switch (pImportance) {
				case RunningAppProcessInfo.IMPORTANCE_FOREGROUND:
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Method will get Current power source state and Battery level for the
	 * device s * @param intent
	 */
	private void getPowerSourceStateandBatteryLevel(Intent intent) {
		// AC Power Source boolean flag
		Boolean mPowerSource = false;
		/** Battery level */
		int mBatteryLevel = 0;
		// Battery temperature //
		int mBatteryTemp;
		int status = -1;
		final String action = intent.getAction();
		mBatteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
		if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
			final Bundle extras = intent.getExtras();
			if (extras != null) {
				status = extras.getInt(BatteryManager.EXTRA_PLUGGED, -1);
				final int rawlevel = intent.getIntExtra("level", -1);
				final int scale = intent.getIntExtra("scale", -1);
				int level = -1;
				if (rawlevel >= 0 && scale > 0) {
					level = (rawlevel * 100) / scale;
				}
				mBatteryLevel = level;
			}
			if (status != -1) {
				switch (status) {
				case BatteryManager.BATTERY_PLUGGED_USB:
					mPowerSource = true;
					break;
				case BatteryManager.BATTERY_PLUGGED_AC:
					mPowerSource = true;
					break;
				case BatteryManager.BATTERY_STATUS_DISCHARGING:
					mPowerSource = false;
				default:
					mPowerSource = false;
					break;
				}
			}
		}
		if (DEBUG) {
			Log.d(TAG, "received battery level: " + mBatteryLevel);
			Log.d(TAG, "received battery temp: " + mBatteryTemp / 10 + "C");
			Log.d(TAG, "received power source " + mPowerSource);
		}
		writeTraceLineToAROTraceFile(mBatteryTracewriter, mBatteryLevel + " "
				+ mBatteryTemp / 10 + " " + mPowerSource, true);
	}

	/**
	 * Starts the battery trace
	 */
	private void startAROBatteryLevelMonitor() {
		if (mBatteryLevelReceiver == null) {
			mBatteryLevelReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					getPowerSourceStateandBatteryLevel(intent);

				}
			};
		}
		if (mBatteryLevelReceiver != null) {
			registerReceiver(mBatteryLevelReceiver, new IntentFilter(
					Intent.ACTION_BATTERY_CHANGED));
		}
	}

	/**
	 * Stop the battery trace
	 */
	private void stopAROBatteryLevelMonitor() {
		if (mBatteryLevelReceiver != null) {
			unregisterReceiver(mBatteryLevelReceiver);
			mBatteryLevelReceiver = null;
		}
	}

	/**
	 * Creates and registers the broad cast receiver that listens for the screen
	 * rotation and and writes the screen rotation time to the
	 * "screen_rotations" file.
	 */
	private void startAroScreenRotationMonitor() {
		if (mScreenRotationReceiver == null) {

			mScreenRotationReceiver = new BroadcastReceiver() {

				@Override
				public void onReceive(Context context, Intent intent) {
					if (intent.getAction().equals(
							Intent.ACTION_CONFIGURATION_CHANGED)) {

						Configuration newConfig = getResources()
								.getConfiguration();
						if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
							writeTraceLineToAROTraceFile(
									mScreenRotationTracewriter, LANDSCAPE_MODE,
									true);
						} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
							writeTraceLineToAROTraceFile(
									mScreenRotationTracewriter, PORTRAIT_MODE,
									true);
						}

					}

				}
			};
		}
		registerReceiver(mScreenRotationReceiver, new IntentFilter(
				Intent.ACTION_CONFIGURATION_CHANGED));

	}

	/**
	 * Unregisters the screen rotation broadcast receiver.
	 */
	private void stopAroScreenRotationMonitor() {
		if (mScreenRotationReceiver != null) {
			unregisterReceiver(mScreenRotationReceiver);
			mScreenRotationReceiver = null;
		}
	}

	/**
	 * Handles processing when an AROCollectorTraceService object is binded to
	 * content. Overrides the android.app.Service#onBind method.
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

}
