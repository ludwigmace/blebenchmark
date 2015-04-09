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
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	protected static final String TAG = "BENCH";
	
	String myFingerprint;
	TextView textBanner;
	Button btnSend;
	Button btnAdvertise;
	
    private BleService simpBleService;

    private Map<String, BenchBuddy> benchBuddies;
    
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
            
            
            // we're connected, so add this peer to our list of buddies
            if (action == BleService.ACTION_CONNECTED) {
            
            	BenchBuddy newBuddy = new BenchBuddy();
            	
            	newBuddy.SenderAddress = extras.getString("REMOTE_ADDR");
            	
            	benchBuddies.put(extras.getString("REMOTE_ADDR"), newBuddy);
            	
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
		
		
        Intent simpBleIntent = new Intent(this, BleService.class);
        bindService(simpBleIntent, mServiceConnection, BIND_AUTO_CREATE);
        
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
		
		
	}
	
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(simpBleReceiver, makeGattUpdateIntentFilter());
        
        if (simpBleService != null) {
            //do what we want to do after Resuming
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleService.ACTION_CONNECTED);
        intentFilter.addAction(BleService.ACTION_DISCONNECTED);
        intentFilter.addAction(BleService.ACTION_NEGOTIATING);
        intentFilter.addAction(BleService.INCOMING_PACKET);
        intentFilter.addAction(BleService.NEW_MESSAGE);
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
	
	
	
	public void handleButtonLook(View view) {
		simpBleService.LookAround(5000);
	}
	
	public void handleButtonAdvertise(View view) {
		if (btnAdvertise.getText().toString().equalsIgnoreCase("SHOW")) {
			btnAdvertise.setText("HIDE");
			simpBleService.ShowYourself();
		} else {
			btnAdvertise.setText("SHOW");
			simpBleService.HideYourself();
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
	
		byte[] newMsg = GenerateMessage(3000);
		
		String remoteAddress = benchBuddies.keySet().iterator().next();
		
		String result = simpBleService.SendMessage(remoteAddress, newMsg);
		
		if (result == null) {
			Log.v(TAG, "no message queued to send");
		} else {
			Log.v(TAG, "message queued to send w/ id " + result);
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
