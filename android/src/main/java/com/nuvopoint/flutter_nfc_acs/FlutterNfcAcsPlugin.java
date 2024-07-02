package com.nuvopoint.flutter_nfc_acs;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import com.acs.bluetooth.Acr1255uj1Reader;
import com.acs.bluetooth.BluetoothReader;
import com.acs.bluetooth.BluetoothReaderGattCallback;
import com.acs.bluetooth.BluetoothReaderManager;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;

import static android.content.ContentValues.TAG;

/**
 * FlutterNfcAcsPlugin
 */
public class FlutterNfcAcsPlugin extends BluetoothPermissions implements FlutterPlugin, ActivityAware, MethodCallHandler, StreamHandler, LifecycleObserver {
  // The method channel's commands
  private static final String CONNECT = "CONNECT";
  private static final String DISCONNECT = "DISCONNECT";
  private static final String SEND_APDU = "SEND_APDU";

  // Error codes
  // TODO: Figure out how to transmit errors that are detected in listeners.
  // static final String ERROR_NO_BLUETOOTH_MANAGER = "no_bluetooth_manager";
  // static final String ERROR_GATT_CONNECTION_FAILED = "gatt_connection_failed";
  private static final String ERROR_MISSING_ADDRESS = "missing_address";
  private static final String ERROR_MISSING_APDU_COMMAND = "missing_apdu_command";
  private static final String ERROR_DEVICE_NOT_FOUND = "device_not_found";
  private static final String ERROR_DEVICE_NOT_SUPPORTED = "device_not_supported";
  static final String ERROR_NO_PERMISSIONS = "no_permissions";

  // Flutter channels
  private MethodChannel channel;
  private EventChannel devicesChannel;
  // These are hooked up on a successful connection to a device.
  private EventChannel deviceBatteryChannel;
  private EventChannel deviceStatusChannel;
  private EventChannel deviceCardChannel;

  // The sink for status events
  private EventChannel.EventSink statusEvents;
  private static final String CONNECTED = "CONNECTED";
  private static final String CONNECTING = "CONNECTING";
  private static final String DISCONNECTED = "DISCONNECTED";
  private static final String DISCONNECTING = "DISCONNECTING";
  private static final String UNKNOWN_CONNECTION_STATE = "UNKNOWN_CONNECTION_STATE";

  // Sleep mode options
  /*private static final byte SLEEP_60_SEC = 0x00;
  private static final byte SLEEP_90_SEC = 0x01;
  private static final byte SLEEP_120_SEC = 0x02;
  private static final byte SLEEP_180_SEC = 0x03;*/
  private static final byte SLEEP_NEVER = 0x04;

  // "ACR1255U-J1 Auth" in text;
  private static final byte[] DEFAULT_1255_MASTER_KEY = {(byte) 65, 67, 82, 49, 50, 53, 53, 85, 45, 74, 49, 32, 65, 117, 116, 104};

  private ActivityPluginBinding activityBinding;
  private BluetoothManager bluetoothManager;
  private BluetoothGatt mBluetoothGatt;
  private BluetoothReaderManager mBluetoothReaderManager;
  private BluetoothReaderGattCallback mGattCallback;
  private Context context;

  // A DeviceScanner scans for bluetooth devices
  private DeviceScanner deviceScanner;
  private BatteryStreamHandler batteryStreamHandler;
  private CardStreamHandler cardStreamHandler;

  // Connection state
  private int mConnectState = BluetoothReader.STATE_DISCONNECTED;

  // Variables for pending permissions
  private MethodCall pendingMethodCall;
  private MethodChannel.Result pendingResult;
  private boolean pendingResultComplete = false;

  // The address is kept in memory in case of life cycle events
  private String address;

  @Override
  public void onAttachedToEngine(final @NonNull FlutterPluginBinding flutterPluginBinding) {
    context = flutterPluginBinding.getApplicationContext();
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter.nuvopoint.com/nfc/acs");
    devicesChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter.nuvopoint.com/nfc/acs/devices");
    deviceBatteryChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter.nuvopoint.com/nfc/acs/device/battery");
    deviceStatusChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter.nuvopoint.com/nfc/acs/device/status");
    deviceCardChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter.nuvopoint.com/nfc/acs/device/card");
  }

  @Override
  public void onDetachedFromEngine(final @NonNull FlutterPluginBinding binding) {
    context = null;
  }

  @Override
  public void onAttachedToActivity(final @NonNull ActivityPluginBinding binding) {
    activityBinding = binding;
    init();
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    dispose();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(final @NonNull ActivityPluginBinding binding) {
    activityBinding = binding;
    init();
  }

  @Override
  public void onDetachedFromActivity() {
    dispose();
  }

  @Override
  protected Activity getActivity() {
    return activityBinding.getActivity();
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    pendingMethodCall = call;
    pendingResult = result;
    pendingResultComplete = false;

    switch (call.method) {
      case CONNECT:
        if (!hasPermissions()) {
          requestPermissions();
          return;
        }

        address = call.argument("address");
        if (address == null) {
          new Handler(Looper.getMainLooper()).post(() -> result.error(ERROR_MISSING_ADDRESS, "The address argument cannot be null", null));
          return;
        }

        if (connectToReader()) {
          new Handler(Looper.getMainLooper()).post(() -> result.success(null));
        } else {
          new Handler(Looper.getMainLooper()).post(() -> result.error(ERROR_DEVICE_NOT_FOUND, "The bluetooth device could not be found", null));
        }

        break;
      case DISCONNECT:
        if (!hasPermissions()) {
          requestPermissions();
          return;
        }

        disconnectFromReader();
        new Handler(Looper.getMainLooper()).post(() -> result.success(null));
        break;

      case SEND_APDU:
        if (!hasPermissions()) {
          requestPermissions();
          return;
        }

        doSendApdu(call, result);
        break;
      default:
    }
  }

  private void doSendApdu(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    String encodedString = call.argument("data");
    if (encodedString == null) {
      new Handler(Looper.getMainLooper()).post(() -> result.error(ERROR_MISSING_APDU_COMMAND, "The apdu command argument cannot be null", null));
      return;
    }

    String[] parts = encodedString.split("\\|");
    if (parts.length == 1) {
      Log.i(TAG, "Decoding: " +  encodedString);
      final byte[] decoded = android.util.Base64.decode(encodedString, android.util.Base64.DEFAULT);
      Log.i(TAG, "byte array size " +  decoded.length);
      cardStreamHandler.sendApdu(decoded);
    }

    if (parts.length > 1) {
      byte[][] data = new byte[parts.length][];

      for (int c = 0; c < parts.length; c++) {
        Log.i(TAG, "Decoding: " +  parts[c]);
        data[c] = android.util.Base64.decode(parts[c], android.util.Base64.DEFAULT);
        Log.i(TAG, "byte array size " +  data[c].length);
      }

      cardStreamHandler.sendMultipleApduWithMergedResult(data);
    }

    new Handler(Looper.getMainLooper()).post(() -> result.success(null));
  }

  // Emits status events on listen
  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    statusEvents = events;
    notifyStatusListeners();
  }

  @Override
  public void onCancel(Object arguments) {
    statusEvents = null;
  }

  @Override
  protected void afterPermissionsGranted() {
    if (pendingResultComplete) return;
    pendingResultComplete = true;
    if (pendingMethodCall != null) {
      switch (pendingMethodCall.method) {
        case CONNECT:
          address = pendingMethodCall.argument("address");
          if (address == null) {
            new Handler(Looper.getMainLooper()).post(() -> {
              if (pendingMethodCall != null) {
                pendingResult.error(ERROR_MISSING_ADDRESS, "The address argument cannot be null", null);
              }
            });
            return;
          }

          if (connectToReader()) {
            new Handler(Looper.getMainLooper()).post(() -> {
              if (pendingMethodCall != null) {
                pendingResult.success(null);
              }
            });
          } else {
            new Handler(Looper.getMainLooper()).post(() -> {
              if (pendingMethodCall != null) {
                pendingResult.error(ERROR_DEVICE_NOT_FOUND, "The bluetooth device could not be found", null);
              }
            });
          }
          break;
        case DISCONNECT:
          disconnectFromReader();
          new Handler(Looper.getMainLooper()).post(() -> {
            if (pendingMethodCall != null) {
              pendingResult.success(null);
            }
          });
          break;
        case SEND_APDU:
          doSendApdu(pendingMethodCall, pendingResult);
          break;
        default:
      }
    }
  }

  @Override
  protected void afterPermissionsDenied() {
    if (pendingResultComplete) return;
    pendingResultComplete = true;
    new Handler(Looper.getMainLooper()).post(() -> {
      if (pendingResult != null) {
        pendingResult.error(ERROR_NO_PERMISSIONS, "Location permissions are required", null);
      }
    });
  }

  private void init() {
    Lifecycle lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(activityBinding);
    lifecycle.addObserver(this);
    bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    if (bluetoothManager == null) return;

    setupReaderManager();
    setupGattCallback();

    channel.setMethodCallHandler(this);
    activityBinding.addRequestPermissionsResultListener(this);

    deviceScanner = new DeviceScanner(bluetoothManager.getAdapter(), activityBinding.getActivity());
    devicesChannel.setStreamHandler(deviceScanner);
    activityBinding.addRequestPermissionsResultListener(deviceScanner);

    deviceStatusChannel.setStreamHandler(this);

    cardStreamHandler = new CardStreamHandler();
    deviceCardChannel.setStreamHandler(cardStreamHandler);

    batteryStreamHandler = new BatteryStreamHandler();
    deviceBatteryChannel.setStreamHandler(batteryStreamHandler);
  }

  private void dispose() {
    disconnectFromReader();

    devicesChannel.setStreamHandler(null);
    channel.setMethodCallHandler(null);

    deviceCardChannel.setStreamHandler(null);
    cardStreamHandler.dispose();

    deviceBatteryChannel.setStreamHandler(null);
    batteryStreamHandler.dispose();

    deviceStatusChannel.setStreamHandler(null);

    activityBinding.removeRequestPermissionsResultListener(deviceScanner);
    activityBinding.removeRequestPermissionsResultListener(this);

    mGattCallback.setOnConnectionStateChangeListener(null);
    mGattCallback = null;
  }

  /**
   * The reader manager is responsible for setting up all the event streams when a compatible device is detected.
   */
  private void setupReaderManager() {
    // When a reader is detected.
    mBluetoothReaderManager = new BluetoothReaderManager();
    mBluetoothReaderManager.setOnReaderDetectionListener(reader -> {
      if (!(reader instanceof Acr1255uj1Reader)) {
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.error(ERROR_DEVICE_NOT_SUPPORTED, "Device not supported", null);
          }
        });
        Log.w(TAG, "Reader not supported");
        disconnectFromReader();
        return;
      }

      batteryStreamHandler.setReader(reader);
      setupAuthenticationListener(reader);

      reader.setOnEnableNotificationCompleteListener((bluetoothReader, result) -> {
        if (result != BluetoothGatt.GATT_SUCCESS) {
          Log.w(TAG, "Enabling notifications failed");
        } else if (!bluetoothReader.authenticate(DEFAULT_1255_MASTER_KEY)) {
          Log.w(TAG, "Card reader not ready");
        }
      });

      // Enables the reader's battery level, card status and response notifications.
      if (!reader.enableNotification(true)) {
        Log.w(TAG, "ENABLE NOTIFICATIONS NOT READY!");
      }
    });
  }

  private void setupAuthenticationListener(BluetoothReader reader) {
    reader.setOnAuthenticationCompleteListener((r, errorCode) -> {
      if (errorCode == BluetoothReader.ERROR_SUCCESS) {
        Log.i(TAG, "Authentication successful");

        // When a compatible reader is detected, we hook up the event streams.
        cardStreamHandler.setReader(r);

        reader.setOnEscapeResponseAvailableListener((re, response, code) -> {
          re.setOnEscapeResponseAvailableListener(null);
          if (code == BluetoothReader.ERROR_SUCCESS) {
            cardStreamHandler.startPolling();
          } else {
            Log.w(TAG, "Authentication failed");
          }
        });

        final byte[] sleepModeFormat = {(byte) 0xE0, 0x00, 0x00, 0x48, SLEEP_NEVER};
        reader.transmitEscapeCommand(sleepModeFormat);
      } else {
        Log.w(TAG, "Authentication failed");
      }
    });
  }

  /**
   * Monitors the connection, and if one is established, detects the reader type in the other end.
   */
  private void setupGattCallback() {
    // When a connection to GATT is established.
    mGattCallback = new BluetoothReaderGattCallback();
    mGattCallback.setOnConnectionStateChangeListener((gatt, state, newState) -> {
      if (state != BluetoothGatt.GATT_SUCCESS) {
        setConnectionState(BluetoothReader.STATE_DISCONNECTED);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
          Log.w(TAG, "Could not connect to GATT");
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
          Log.w(TAG, "Could not disconnect from GATT");
        }
        return;
      }

      setConnectionState(newState);

      if (newState == BluetoothProfile.STATE_CONNECTED) {
        mBluetoothReaderManager.detectReader(gatt, mGattCallback);
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        setConnectionState(BluetoothReader.STATE_DISCONNECTED);
      }
    });
  }

  @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
  public void connectIfDisconnected() {
    if (address != null && mConnectState == BluetoothReader.STATE_DISCONNECTED) {
      connectToReader();
    }
  }

  public boolean connectToReader() {
    if (address == null) {
      return false;
    }

    if (bluetoothManager == null) {
      setConnectionState(BluetoothReader.STATE_DISCONNECTED);
      Log.e(TAG, "BluetoothManager was null - cannot connect. The device might not have a bluetooth adapter.");
      return false;
    }

    BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
    if (!bluetoothAdapter.isEnabled()) {
      Log.w(TAG, "Bluetooth was not enabled!");
      return false;
    }

    final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);

    if (device == null) {
      Log.w(TAG, "Device not found. Unable to connect.");
      return false;
    }

    if (mBluetoothGatt != null) {
      mBluetoothGatt.disconnect();
      mBluetoothGatt.close();
    }

    // Connect to the GATT server.
    setConnectionState(BluetoothReader.STATE_CONNECTING);
    mBluetoothGatt = device.connectGatt(context, false, mGattCallback);

    return true;
  }

  /**
   * Disconnects the reader and releases resources that are dependant on being connected, which are irrelevant when disconnected.
   */
  private void disconnectFromReader() {
    // Close existing GATT connection
    if (mBluetoothGatt != null) {
      mBluetoothGatt.disconnect();
    }

    setConnectionState(BluetoothReader.STATE_DISCONNECTED);
  }

  private void setConnectionState(int connectionState) {
    mConnectState = connectionState;
    notifyStatusListeners();
  }

  private void notifyStatusListeners() {
    // We can't send a status back if no one is listening for it.
    switch (mConnectState) {
      case BluetoothReader.STATE_CONNECTED:
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.success(CONNECTED);
          }
        });
        break;
      case BluetoothReader.STATE_CONNECTING:
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.success(CONNECTING);
          }
        });
        break;
      case BluetoothReader.STATE_DISCONNECTED:
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.success(DISCONNECTED);
          }
        });
        break;
      case BluetoothReader.STATE_DISCONNECTING:
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.success(DISCONNECTING);
          }
        });
        break;
      default:
        new Handler(Looper.getMainLooper()).post(() -> {
          if (statusEvents != null) {
            statusEvents.success(UNKNOWN_CONNECTION_STATE);
          }
        });
    }
  }
}
