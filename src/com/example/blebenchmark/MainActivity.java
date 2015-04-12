package com.example.blebenchmark;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;

import simpble.ByteUtilities;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	protected static final String TAG = "BENCH";
	
	String myFingerprint;
	TextView textBanner;
	Button btnSend;
	Button btnAdvertise;
	RadioButton btnNotify;
	RadioButton btnIndicate;
	
	Spinner spinMessageSize;
	
	TextView startReceive;
	TextView stopReceive;
	TextView messageDetails;
	
    private BleService simpBleService;
    private String peripheralTransportMode;

    private Map<String, BenchBuddy> benchBuddies;
    
    private long MessageSize;
    
    // Code to manage Service lifecycle, from android le gatt sample
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
        	simpBleService = ((BleService.LocalBinder) service).getService();
        	
        	String result = simpBleService.initialize(); 
        	
            if (result.contains("fail")) {
                Log.e(TAG, "bluetooth problems!");
                finish();
            } else {
            	simpBleService.SetPeripheralTransportMode(peripheralTransportMode);
            	
            	if (result.equalsIgnoreCase("success_adv")) {
            		btnAdvertise.setEnabled(true);
            		btnAdvertise.setText("SHOW");
            	} else {
            		btnAdvertise.setEnabled(false);
            	}
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        	simpBleService = null;
        }


    };

    private final BroadcastReceiver simpBleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();            
            Bundle extras = intent.getExtras();
            
            Log.v(TAG, action + " intent received");

            // we've received a whole message, get its payload and the buddy who sent it
            if (action == BleService.ACTION_MSG_RECEIVED) {
            	BenchBuddy buddy = benchBuddies.get(extras.getString("REMOTE_ADDR"));
            	
    			byte[] payload = extras.getByteArray("MSG_PAYLOAD");
    			final String remoteAddress = extras.getString("REMOTE_ADDR");
    			final int parentMessageId = extras.getInt("PARENT_MSG_ID");
    			final boolean messageIntact = extras.getBoolean("INTEGRITY_PASS");
    			
    			if (messageIntact) {
    				messageDetails.setText("rcvd msg " + parentMessageId + ", " + payload.length + " bytes, " + "good");
    			} else {
    				messageDetails.setText("rcvd msg " + parentMessageId + ", " + payload.length + " bytes, " + "bad");
    			}
    			
    			stopReceive.setText("stop recv:" + String.valueOf(System.currentTimeMillis()));
    			
            }
            
            if (action == BleService.INCOMPLETE_SEND) {
            	setBanner(extras.getString("REMOTE_ADDR") + "(" + extras.getInt("PARENT_MSG_ID") + ")"  + " lacking " + extras.getInt("MISSING_PACKETS") + " packets");
            }
            
            if (action == BleService.INCOMPLETE_RECEIVE) {
            	setBanner("incoming msg " + extras.getInt("PARENT_MSG_ID") + " lacking " + extras.getInt("MISSING_PACKETS") + " packets");
            }
            
            // we're connected, so add this peer to our list of buddies
            if (action == BleService.ACTION_CONNECTED) {
            
            	BenchBuddy newBuddy = new BenchBuddy();
            	
            	newBuddy.SenderAddress = extras.getString("REMOTE_ADDR");
            	
            	benchBuddies.put(extras.getString("REMOTE_ADDR"), newBuddy);
            	
            	setBanner("connected");
            	
            }
            
            // remove this peer from our map
            if (action == BleService.ACTION_DISCONNECTED) {
            	setBanner("disconnected");
            	benchBuddies.remove(extras.getString("REMOTE_ADDR"));
            }
            
            if (action == BleService.ACTION_NEGOTIATING) {
            	setBanner("negotiating");
            }
            
            if (action == BleService.NEW_MESSAGE) {
                if (extras != null) {
                	
                	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
                	final int packets = extras.getInt("PACKET_TOTAL");
                	final String messageHash = extras.getString("MSG_HASH");
                	
                	BenchMessage m = new BenchMessage();
                	
                	// store the time this message started
                	m.MillisecondStart = System.currentTimeMillis();
                	m.ExpectedPackets = packets;
                	
                	// find our Benchmarking buddy and add this message to their list
                	BenchBuddy b = benchBuddies.get(extras.getString("REMOTE_ADDR"));
                	b.benchMessages.append(parent_msgid, m);
                	
                	messageDetails.setText("incoming (" + parent_msgid + ") of size " + (packets * 17));
                	startReceive.setText("start recv:" + String.valueOf(m.MillisecondStart));
                	stopReceive.setText("waiting . . .");
                	
                	setBanner("msg " + messageHash + " - " + String.valueOf(packets) + " packets");
                } else {
                	setBanner("new msg, extras null");
                }
            }
            
            // packet is coming in . . .
            if (action == BleService.INCOMING_PACKET) {
                if (extras != null) {
                	
                	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
                	final int current_packet = extras.getInt("CURRENT_PACKET");
                	
                	setBanner("msg " + String.valueOf(parent_msgid) + " - " + String.valueOf(current_packet) + " packet");
                } else {
                	setBanner("new msg, extras null");
                }
            }
            
            if (action == BleService.MESSAGE_DELIVERED) {

            	
                if (extras != null) {
                	final String remote_addr = extras.getString("REMOTE_ADDR");
                	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
                	final String msg_hash = extras.getString("MSG_HASH");
                	
                	setBanner("msg " + String.valueOf(parent_msgid) + " delivered to " + remote_addr);
                } else {
                	setBanner("new msg, extras null");
                }
            }
            
            if (action == BleService.MESSAGE_START_SEND) {
                if (extras != null) {
                	
                	final String remote_addr = extras.getString("REMOTE_ADDR");
                	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
                	final String msg_hash = extras.getString("MSG_HASH");
                	
                	setBanner("sending msg " + String.valueOf(parent_msgid) + " to " + remote_addr);
                } else {
                	setBanner("new msg, extras null");
                }
            }
            
            if (action == BleService.MESSAGE_UPDATE) {
                if (extras != null) {
                	
                	final String remote_addr = extras.getString("REMOTE_ADDR");
                	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
                	final String msg_hash = extras.getString("MSG_HASH");
                	final int packets_sent = extras.getInt("PACKETS_SENT");
                	
                	setBanner("msg " + String.valueOf(parent_msgid) + " - " + String.valueOf(packets_sent) + " packets sent");
                } else {
                	setBanner("new msg, extras null");
                }
            }
            
            Log.v(TAG, "service says: " + action);
        }
    };
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		textBanner = (TextView) findViewById(R.id.banner);
		btnSend = (Button) findViewById(R.id.send);
		btnAdvertise = (Button) findViewById(R.id.advert);
		
		btnNotify = (RadioButton) findViewById(R.id.radio_notify);
		btnIndicate = (RadioButton) findViewById(R.id.radio_indicate);
		
		
		startReceive = (TextView) findViewById(R.id.msg_start_receive);
		stopReceive = (TextView) findViewById(R.id.msg_stop_receive);
		messageDetails = (TextView) findViewById(R.id.msg_details);
		
        Intent simpBleIntent = new Intent(this, BleService.class);
        bindService(simpBleIntent, mServiceConnection, BIND_AUTO_CREATE);
        
        spinMessageSize = (Spinner) findViewById(R.id.msg_size);
        
        // get an identifier for this installation
        String myIdentifier = Installation.id(this, false);
        
        // init the rsaKey object
        KeyStuff rsaKey = null;
        
		try {
			rsaKey = new KeyStuff(this, myIdentifier);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		// get our fingerprint
		myFingerprint = ByteUtilities.bytesToHex(rsaKey.PuFingerprint());
		
		benchBuddies = new HashMap<String, BenchBuddy>();
		
		if (!(btnNotify.isChecked() || btnIndicate.isChecked())) {
			btnNotify.setChecked(true);
			peripheralTransportMode = "notify";
		}
		
		
	}
	
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(simpBleReceiver, makeUpdateIntentFilter());
        
        if (simpBleService != null) {
            //do what we want to do after Resuming
        }
    }

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleService.ACTION_CONNECTED);
        intentFilter.addAction(BleService.ACTION_DISCONNECTED);
        intentFilter.addAction(BleService.ACTION_NEGOTIATING);
        intentFilter.addAction(BleService.INCOMING_PACKET);
        intentFilter.addAction(BleService.ACTION_MSG_RECEIVED);
        intentFilter.addAction(BleService.NEW_MESSAGE);
        intentFilter.addAction(BleService.INCOMPLETE_SEND);
        intentFilter.addAction(BleService.INCOMPLETE_RECEIVE);
        
        intentFilter.addAction(BleService.MESSAGE_DELIVERED);
        intentFilter.addAction(BleService.MESSAGE_START_SEND);
        intentFilter.addAction(BleService.MESSAGE_UPDATE);
        
        return intentFilter;
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(simpBleReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        simpBleService = null;
    }
	
	public void onRadioButtonClicked(View v) {
		boolean checked = ((RadioButton) v).isChecked();
		
		switch(v.getId()) {
		case R.id.radio_notify:
			if (checked) {
				peripheralTransportMode = "notify";
			}
			break;
			
		case R.id.radio_indicate:
			if (checked) {
				peripheralTransportMode = "indicate";
			}
			break;
		}
		
		simpBleService.SetPeripheralTransportMode(peripheralTransportMode);
		
	}
    
    
	
	public void handleButtonLook(View view) {
		simpBleService.LookAround(5000);
		setBanner("looking around");
	}
	
	public void handleButtonAdvertise(View view) {
		if (btnAdvertise.getText().toString().equalsIgnoreCase("SHOW")) {
			btnAdvertise.setText("HIDE");
			simpBleService.ShowYourself();
			setBanner("advertising");
		} else {
			btnAdvertise.setText("SHOW");
			simpBleService.HideYourself();
			setBanner("hidden");
		}

	}
	

	

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	private void setBanner(String msg) {

		final String message = msg;
		
		runOnUiThread(new Runnable() {
			  public void run() {
				  textBanner.setText(message);
			  }
			});
		
	}
	
	public void handleButtonSend(View v) {
	
		String msgSize = String.valueOf(spinMessageSize.getSelectedItem());
		
		String msgUnits = msgSize.substring(msgSize.length()-2);
		int msgUnitCount = Integer.parseInt(msgSize.substring(0, msgSize.length()-3));
		
		int byteSize = 0;
		
		if (msgUnits.equalsIgnoreCase("MB")) {
			byteSize = msgUnitCount * 1000000;
		} else if (msgUnits.equalsIgnoreCase("kB")) {
			byteSize = msgUnitCount * 1000;
		} else if (msgUnits.equalsIgnoreCase("hB")) {
			byteSize = msgUnitCount * 100;
		}
		
		if (byteSize > 0) {
		
			byte[] newMsg = GenerateMessage(byteSize);
			
			String remoteAddress = benchBuddies.keySet().iterator().next();
			
			String result = simpBleService.SendMessage(remoteAddress, newMsg);
			
			if (result == null) {
				Log.v(TAG, "no message queued to send");
			} else {
				Log.v(TAG, "message queued to send w/ id " + result);
			}
		
		} else {
			Log.v(TAG, "byte size 0");
		}
		
	}
	

	private byte[] GenerateMessage(int MessageSize) {
		// get the lorem text from file
		byte[] bytesLorem = null;
		byte[] bytesMessage = null;
		InputStream is = this.getResources().openRawResource(R.raw.lorem);
    			
		int currentMessageLength = 0;
		int maxcount = 0;
		
		while ((currentMessageLength < MessageSize) && maxcount < 1000) {
			maxcount++;
	    	try {
	    		if (currentMessageLength == 0) {
	    			bytesMessage = ByteStreams.toByteArray(is);
	    		}
	    		is.reset();
	    		bytesLorem = ByteStreams.toByteArray(is);
			} catch (IOException e) {
				e.printStackTrace();
			}
	    	
	    	try {
				is.reset();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    	
	    	bytesMessage = Bytes.concat(bytesMessage, bytesLorem);
	    	
	    	currentMessageLength = bytesMessage.length;
    	
		}
		
		return Arrays.copyOf(bytesMessage, MessageSize);
	}
	
}
