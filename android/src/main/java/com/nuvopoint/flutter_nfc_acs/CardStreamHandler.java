package com.nuvopoint.flutter_nfc_acs;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.acs.bluetooth.Acr1255uj1Reader;
import com.acs.bluetooth.BluetoothReader;

import java.util.Arrays;

import io.flutter.plugin.common.EventChannel;

import static android.content.ContentValues.TAG;

/**
 * A StreamHandler that emits the IDs of scanned cards.
 */
class CardStreamHandler implements EventChannel.StreamHandler {
  private static final byte[] AUTO_POLLING_START = {(byte) 0xE0, 0x00, 0x00, 0x40, 0x01};
  private static final byte[] AUTO_POLLING_STOP = {(byte) 0xE0, 0x00, 0x00, 0x40, 0x00};
  private static final String requestCardId = "FFCA000000";
  private BluetoothReader reader;
  private EventChannel.EventSink events;
  private byte[] apduToSend;
  private String apduResponse = "";
  private byte[][] multipleApduToSend;
  private int expectedApduResponseCount = 0;
  private int cardStatus = -1;

  void setReader(final BluetoothReader reader) {
    if (reader instanceof Acr1255uj1Reader) {
      this.reader = reader;

      reader.setOnResponseApduAvailableListener((_r, response, errorCode) -> {
        if (errorCode == BluetoothReader.ERROR_SUCCESS) {
          new Handler(Looper.getMainLooper()).post(() -> {
            if (events != null) {
              final String responseStr = Utils.toHexString(Arrays.copyOf(response, response.length - 2)).trim();
              this.apduResponse = this.apduResponse == "" ? responseStr : (this.apduResponse + " " + responseStr);
              this.expectedApduResponseCount--;
              Log.i(TAG, "Received response: " + responseStr + ". " + this.expectedApduResponseCount + " more to go");

              if (this.expectedApduResponseCount <= 0) {
                this.multipleApduToSend = null;
                events.success(this.apduResponse);
                this.apduResponse = "";
                return;
              }

              final byte[] command = this.multipleApduToSend[this.multipleApduToSend.length - this.expectedApduResponseCount];
              Log.i(TAG, "Sending next command: " + Utils.toHexString(command));
              reader.transmitApdu(command);
            }
          });
        } else {
          this.expectedApduResponseCount = 0;
          this.multipleApduToSend = null;
          new Handler(Looper.getMainLooper()).post(() -> {
            if (events != null) {
              events.error("unknown_reader_error", String.valueOf(errorCode), null);
            }
          });
        }
      });

      reader.setOnCardStatusChangeListener((bluetoothReader, cardStatusCode) -> {
        this.cardStatus = cardStatusCode;
        Log.i(TAG, "Card status: " + getCardStatusString(cardStatusCode));
        if (cardStatusCode != BluetoothReader.CARD_STATUS_PRESENT) return;

        if (multipleApduToSend != null) {
          this.startMultipleApduSend(bluetoothReader);
          return;
        }

        if (apduToSend == null) {
          Log.i(TAG, "Requesting card ID");
          bluetoothReader.transmitApdu(Utils.hexStringToByteArray(requestCardId));
          return;
        }

        this.expectedApduResponseCount = 1;
        Log.i(TAG, "Sending APDU command");
        bluetoothReader.transmitApdu(apduToSend);
        apduToSend = null;
      });
    } else {
      Log.i(TAG, "Card stream not supported for this device");
    }
  }

  private String getCardStatusString(int cardStatus) {
    if (cardStatus == BluetoothReader.CARD_STATUS_ABSENT) {
      return "Absent";
    } else if (cardStatus == BluetoothReader.CARD_STATUS_PRESENT) {
      return "Present";
    } else if (cardStatus == BluetoothReader.CARD_STATUS_POWERED) {
      return "Powered";
    } else if (cardStatus == BluetoothReader.CARD_STATUS_POWER_SAVING_MODE) {
      return "Power saving mode";
    }

    return "Unknown";
  }

  void startPolling() {
    if (reader != null) {
      reader.transmitEscapeCommand(AUTO_POLLING_START);
    }
  }

  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    this.events = events;
    startPolling();
  }

  @Override
  public void onCancel(Object arguments) {
    dispose();
  }

  void sendApdu(byte[] data) {
    this.apduToSend = data;
  }

  private void startMultipleApduSend(BluetoothReader reader) {
    Log.i(TAG, "Sending multiple APDU commands");
    this.apduResponse = "";
    Log.i(TAG, "Sending command: " + Utils.toHexString(this.multipleApduToSend[0]));
    reader.transmitApdu(this.multipleApduToSend[0]);
  }

  void sendMultipleApduWithMergedResult(byte[][] data) {
    this.apduToSend = null;
    this.multipleApduToSend = data;
    this.expectedApduResponseCount = data.length;

    if (this.cardStatus == BluetoothReader.CARD_STATUS_PRESENT) this.startMultipleApduSend(this.reader);
  }

  void dispose() {
    if (reader != null) {
      reader.transmitEscapeCommand(AUTO_POLLING_STOP);
      reader.setOnResponseApduAvailableListener(null);
      reader.setOnCardStatusChangeListener(null);
    }
    events = null;
  }
}
