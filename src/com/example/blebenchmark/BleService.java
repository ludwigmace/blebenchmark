package com.example.blebenchmark;

import simpble.BleMessenger;
import simpble.BleStatusCallback;
import android.app.IntentService;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

// adapted from Android sample code
public class BleService extends Service {

	protected static final String TAG = "BLESERVICE";
	BleMessenger bleMessenger;
	
	BluetoothManager btMgr;
	BluetoothAdapter btAdptr;
	
	public static final String ACTION_NEGOTIATING = "negotiating";
	public static final String ACTION_CONNECTED = "connected";
	public static final String ACTION_DISCONNECTED = "disconnected";
	public static final String ACTION_MSG_RECEIVED = "msg_received";
	public static final String INCOMING_PACKET = "packet_in";
	public static final String NEW_MESSAGE = "new_message";
	public static final String INCOMPLETE_SEND = "incomplete_send";
	public static final String INCOMPLETE_RECEIVE = "incomplete_recv";

	public static final String MESSAGE_DELIVERED = "msg_delivered";
	public static final String MESSAGE_START_SEND = "message_start_send";
	public static final String MESSAGE_UPDATE = "message_update_progress";
	public static final String SCANNING_UPDATE = "scanning_update";

	
    private final IBinder mBinder = new LocalBinder();
	
    public class LocalBinder extends Binder {
    	BleService getService() { return BleService.this; }
    }

    @Override
	public IBinder onBind(Intent intent) {
    	return mBinder;
    }

    @Override
	public boolean onUnbind(Intent intent) {
    	// cleanup stuff goes here when the service goes away
    	close();
	    return super.onUnbind(intent);
    }
	    
    public void close() {
    	// actually put cleanup stuff here
    	return;
    }

    public String SendMessage(String remoteAddress, byte[] messageBytes) {
    	String result = "";
    	Log.v(TAG, "send msg to " + remoteAddress);
    	result = bleMessenger.AddMessage(remoteAddress, messageBytes);
    	
    	return result;
    }
    
    public void LookAround(int ms) {
    	bleMessenger.ScanForPeers(ms);
    }
    
    public void StopScan() {
    	bleMessenger.StopScan();
    }
    
    public void SetPeripheralTransportMode(String method) {
    	bleMessenger.SetPeripheralTransportMode(method);
    }
    
    //TODO: maybe better here to check if we're currently advertising?
    public void ShowYourself() {
    	bleMessenger.StartAdvertising();
    }
    
    public void HideYourself() {
    	bleMessenger.StopAdvertising();
    }

    public String initialize() {
    	
    	if (btMgr == null) {
        	btMgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (btMgr == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return "fail_bt_mgr";
            }
        }

        btAdptr = btMgr.getAdapter();
        if (btAdptr == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return "fail_bt_adptr";
        }

        
        bleMessenger = new BleMessenger(btMgr, btAdptr, this, bleMessageStatus);
        
        if (bleMessenger.SupportsAdvertising) {
        	return "success_adv";
        } else {
        	return "success_noadv";
        }
    }
    
    
    
		 	  
	BleStatusCallback bleMessageStatus = new BleStatusCallback() {
		
		@Override
		public void packetsRequeued(String remoteAddress, int messageId, int missingPacketCount, int retryCount) {
			
			Log.v(TAG, "new intent INCOMPLETE_SEND for " + remoteAddress);
			
			final Intent intent = new Intent(INCOMPLETE_SEND);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", messageId);
			extras.putInt("MISSING_PACKETS", missingPacketCount);
			extras.putInt("RETRY_COUNT", retryCount);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
		}
		
		@Override
		public void incomingPacket(String remoteAddress, String payloadDigest, int parentMessageId, int packetsSent) {
			final Intent intent = new Intent(INCOMING_PACKET);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", parentMessageId);
			extras.putInt("CURRENT_PACKET", packetsSent);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}
		
		public void newMessage(String remoteAddress, String messageHash, int messageId, int packetsExpected) {
			final Intent intent = new Intent(NEW_MESSAGE);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", messageId);
			extras.putInt("PACKET_TOTAL", packetsExpected);
			
			extras.putString("MSG_HASH", messageHash);
			
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}
		
		public void peerConnectionStatus(String remoteAddress, int ConnectionStatus) {

			
			if (ConnectionStatus == BleMessenger.CONNECTION_NEGOTIATING) {

				final Intent intent = new Intent(ACTION_NEGOTIATING);
				Bundle extras = new Bundle();
				extras.putString("REMOTE_ADDR", remoteAddress);
				
				intent.putExtras(extras);
				
				sendBroadcast(intent);
				
				Log.v(TAG, "negotiating " + String.valueOf(System.currentTimeMillis()));
				
			} else if (ConnectionStatus ==  BleMessenger.CONNECTION_CONNECTED) {
				Log.v(TAG, "connected " + String.valueOf(System.currentTimeMillis()));

				final Intent intent = new Intent(ACTION_CONNECTED);
				Bundle extras = new Bundle();
				extras.putString("REMOTE_ADDR", remoteAddress);
				
				intent.putExtras(extras);
				
				sendBroadcast(intent);
				
			} else if (ConnectionStatus ==  BleMessenger.CONNECTION_DISCONNECTED) {
				Log.v(TAG, "disconnected " + String.valueOf(System.currentTimeMillis()));
				
				final Intent intent = new Intent(ACTION_DISCONNECTED);
				Bundle extras = new Bundle();
				extras.putString("REMOTE_ADDR", remoteAddress);
				
				intent.putExtras(extras);
				
				sendBroadcast(intent);
				
				
			} else {
				Log.v(TAG, "nothin connected " + String.valueOf(System.currentTimeMillis()));
			}
			
		}

		@Override
		public void handleReceivedMessage(String remoteAddress, int parentMessageId, boolean messageIntact, byte[] MessageBytes) {

			final Intent intent = new Intent(ACTION_MSG_RECEIVED);
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putByteArray("MSG_PAYLOAD", MessageBytes);
			extras.putInt("PARENT_MSG_ID", parentMessageId);
			extras.putBoolean("INTEGRITY_PASS", messageIntact);
			
			intent.putExtras(extras);
			
			sendBroadcast(intent);
			
		}

		@Override
		public void advertisingStatusUpdate(boolean isAdvertising) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void headsUp(String msg) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void missingPackets(String remoteAddress, int messageId, int missingPacketCount) {
			Log.v(TAG, "new intent INCOMPLETE_SEND for " + remoteAddress);
			
			final Intent intent = new Intent(INCOMPLETE_RECEIVE);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", messageId);
			extras.putInt("MISSING_PACKETS", missingPacketCount);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}

		@Override
		public void messageDelivered(String remoteAddress, String payloadDigest, int parentMessageId) {
			final Intent intent = new Intent(MESSAGE_DELIVERED);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", parentMessageId);			
			extras.putString("MSG_HASH", payloadDigest);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}

		@Override
		public void messageSendStart(String remoteAddress, String payloadDigest, int parentMessageId) {
			final Intent intent = new Intent(MESSAGE_START_SEND);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", parentMessageId);			
			extras.putString("MSG_HASH", payloadDigest);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}

		@Override
		public void messageStatusUpdate(String remoteAddress, String payloadDigest, int parentMessageId, int packetsSent) {
			final Intent intent = new Intent(MESSAGE_UPDATE);
			
			Bundle extras = new Bundle();
			extras.putString("REMOTE_ADDR", remoteAddress);
			extras.putInt("PARENT_MSG_ID", parentMessageId);			
			extras.putString("MSG_HASH", payloadDigest);
			extras.putInt("PACKETS_SENT", packetsSent);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}

		@Override
		public void scanningStatusUpdate(boolean isScanning) {
			final Intent intent = new Intent(SCANNING_UPDATE);
			
			Bundle extras = new Bundle();
			extras.putBoolean("SCANNING_UPDATE", isScanning);
			
			intent.putExtras(extras);
			sendBroadcast(intent);
			
		}

		
	};
}
