package com.example.blebenchmark;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;

import simpble.BleApplicationPeer;
import simpble.ByteUtilities;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	protected static final String TAG = "BENCH";
	
	public static final int MSGTYPE_ID = 1;
	public static final int MSGTYPE_PLAIN = 2;
	public static final int MSGTYPE_ENCRYPTED_PAYLOAD = 20;
	public static final int MSGTYPE_ENCRYPTED_KEY = 21;
	public static final int MSGTYPE_DROP = 90;
	
	String myFingerprint;
	TextView textBanner;
	Button btnSend;
	Button btnAdvertise;
	Button btnScan;
	RadioButton btnNotify;
	RadioButton btnIndicate;

	Context ctx;
	
	Spinner spinMessageSize;
	
	TextView startReceive;
	TextView stopReceive;
	TextView messageDetails;
	
	ImageView imageToSend;
	
    private BleService simpBleService;
    private String peripheralTransportMode;

    private Map<String, BenchBuddy> benchBuddies;
    
    private long MessageSize;
    
    private static final int ACTIVITY_CREATE=0;
    
    private FriendsDb dB;

    private Map<String, String> addressesToFriends;
    private Map <String, BleApplicationPeer> bleFriends;  // folks whom i have previously connected to, or i have their id info
    
    KeyStuff rsaKey;
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		// get a handle to our database
		dB = new FriendsDb(this);
		ctx = this;
		
		addressesToFriends = new HashMap<String, String>();
		
		
		imageToSend = (ImageView) findViewById(R.id.img_to_send);
		
		textBanner = (TextView) findViewById(R.id.banner);
		btnSend = (Button) findViewById(R.id.send);
		
		btnAdvertise = (Button) findViewById(R.id.advert);
		btnScan = (Button) findViewById(R.id.search);
		
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
        rsaKey = null;
        
		try {
			rsaKey = new KeyStuff(this, myIdentifier);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		// get our fingerprint
		myFingerprint = ByteUtilities.bytesToHex(rsaKey.PuFingerprint());
		
		benchBuddies = new HashMap<String, BenchBuddy>();
		
		PopulateFriends(); // pull our existing friends from the database
		
		if (!(btnNotify.isChecked() || btnIndicate.isChecked())) {
			btnNotify.setChecked(true);
			peripheralTransportMode = "notify";
		}
		
		
	}
    
    
    private final BroadcastReceiver simpBleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();            
            Bundle extras = intent.getExtras();
            
            Log.v(TAG, action + " intent received");

            // we've received a whole message, get its payload and the buddy who sent it
            if (action == BleService.SCANNING_UPDATE) {
            	final boolean isScanning = extras.getBoolean("SCANNING_UPDATE");
            	
            	if (!isScanning) {
            		setBanner("stopped scanning");
            		btnScan.setText("LOOK");
            	} else {
            		setBanner("started scanning");
            	}
            }
            
            // we've received a whole message, get its payload and the buddy who sent it
            if (action == BleService.ACTION_MSG_RECEIVED) {
            	BenchBuddy buddy = benchBuddies.get(extras.getString("REMOTE_ADDR"));
            	
            	if (buddy != null) {
            	
	    			byte[] payload = extras.getByteArray("MSG_PAYLOAD");
	    			
	    			final String remoteAddress = extras.getString("REMOTE_ADDR");
	    			final int parentMessageId = extras.getInt("PARENT_MSG_ID");
	    			final boolean messageIntact = extras.getBoolean("INTEGRITY_PASS");
	    			
	    			// create a new ApplicationMessage object and build its properties
	    			ApplicationMessage incomingMsg = new ApplicationMessage();
	    			String incomingDigest = incomingMsg.SetRawBytes(payload);

	    			incomingMsg.BuildMessageDetails();
	    			
	    			Log.v(TAG, "incoming msg digest: " + incomingDigest);
	    			
	    			
	    			// get the message type in integer form; should be between 0 and 255 so &0xFF should work
	    			int mt = incomingMsg.MessageType & 0xFF;
	    			
	    			String recipientFingerprint = ByteUtilities.bytesToHex(incomingMsg.RecipientFingerprint);
	    			String senderFingerprint = ByteUtilities.bytesToHex(incomingMsg.SenderFingerprint);
	    			
	    			byte[] messagePayload = incomingMsg.MessagePayload;
	    			byte[] messageHash = incomingMsg.BuildMessageMIC();
	    			
	    			
	    			BenchMessage m = buddy.benchMessages.get(parentMessageId);
	    			
	    			if (m != null) {
	    			
		    			m.MillisecondStop = System.currentTimeMillis();
		    			
		    			String duration = String.valueOf((m.MillisecondStop - m.MillisecondStart) / 1000);
		    			
		    			String intact = "";
		    			
		    			if (messageIntact) {
		    				intact = "good";
		    			} else {
		    				intact = "bad";
		    			}
		    			
		    			messageDetails.setText("rcvd msg " + parentMessageId + ", " + payload.length + " bytes, " + m.IncompleteReceives + " retries, " +  intact);
		    			stopReceive.setText("msg received in " + duration + " seconds");
		    			
		    			switch (mt) {
						case MSGTYPE_ID:
							// if the sender is in our friends list
							if (bleFriends.containsKey(senderFingerprint)) {
								
								// we need to be able to look this person up by incoming address
								addressesToFriends.put(remoteAddress, senderFingerprint);
			
							} else {  // if we actually care who this person is, then store their FP
								//logMessage("a: this guy's FP isn't known to me: " + senderFingerprint.substring(0,20));
								
						        Intent i = new Intent(ctx, AddFriendsActivity.class);
						        i.putExtra("fp", senderFingerprint);
						        i.putExtra("puk", messagePayload);
						        startActivityForResult(i, ACTIVITY_CREATE);
													
						        // TODO: add back to addressesToFriends
							}
							
							
							break;
		    			}
		    			
		    			
	    			}
            	}
    			
            }
            
            if (action == BleService.INCOMPLETE_SEND) {
            	BenchBuddy b = benchBuddies.get(extras.getString("REMOTE_ADDR"));
            	
            	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
            	final int missing_packets = extras.getInt("MISSING_PACKETS");
            	final int retry_count = extras.getInt("RETRY_COUNT");
            	
            	BenchMessage m = b.benchMessages.get(parent_msgid);
            	
            	if (m != null) {
            		m.IncompleteSends++;
                	setBanner("msg " + parent_msgid + ", retry " + retry_count + ", lacking " + missing_packets + " packets");
            	} else {
            		setBanner("unidentified msg " + parent_msgid);
            	}
            	

            }
            
            if (action == BleService.INCOMPLETE_RECEIVE) {
            	BenchBuddy b = benchBuddies.get(extras.getString("REMOTE_ADDR"));
            	
            	final int parent_msgid = extras.getInt("PARENT_MSG_ID");
            	final int missing_packets = extras.getInt("MISSING_PACKETS");
            	
            	BenchMessage m = b.benchMessages.get(parent_msgid);
            	
            	m.IncompleteReceives++;
            	
            	
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
        
        intentFilter.addAction(BleService.SCANNING_UPDATE);
        
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
		if (btnScan.getText().toString().equalsIgnoreCase("LOOK")) {
			btnScan.setText("STOP");
			simpBleService.LookAround(5000);
			setBanner("looking around");
		} else {
			btnScan.setText("LOOK");
			simpBleService.StopScan();
		}
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
	
	public void handleButtonLoadPic(View view) {
		
		// let's pick out a photo
		Intent pickPhoto = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		
		startActivityForResult(pickPhoto, 1);
		
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
		
		if (id == R.id.action_add_message) {
	        Intent i = new Intent(this, AddMessageActivity.class);
	        startActivityForResult(i, ACTIVITY_CREATE);
		}
		
		if (id == R.id.action_add_friends) {
	        Intent i = new Intent(this, AddFriendsActivity.class);
	        startActivityForResult(i, ACTIVITY_CREATE);
		}	
		
		if (id == R.id.action_friends) {
	        Intent i = new Intent(this, FriendsActivity.class);
	        startActivityForResult(i, ACTIVITY_CREATE);
		}
		
		if (id == R.id.action_show_msgs) {
	        Intent i = new Intent(this, ShowMessagesActivity.class);
	        startActivityForResult(i, ACTIVITY_CREATE);
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
		
			//byte[] newMsg = GenerateMessage(byteSize);
			byte[] newMsg = identityMessage().GetAllBytes(); // send an identity message instead
			
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
	
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		
		super.onActivityResult(requestCode, resultCode, data);
		
		if (requestCode == 1) {
			if (resultCode == RESULT_OK) {
				Uri selectedImage = data.getData();
				Bitmap picAsBmp;
				
				try {
					picAsBmp = MediaStore.Images.Media.getBitmap(this.getContentResolver(), selectedImage);
					imageToSend.setImageBitmap(picAsBmp);
					
					// http://stackoverflow.com/questions/10296734/image-uri-to-bytesarray
					InputStream iStream = getContentResolver().openInputStream(selectedImage);
					ByteArrayOutputStream bb = new ByteArrayOutputStream();
					int buffersize =1024;
					byte[] buffer = new byte[buffersize];
					
					int len = 0;
					
					while ((len = iStream.read(buffer)) != -1) {
						bb.write(buffer, 0, len);
					}
				    
					byte[] filebytes = bb.toByteArray();
					
					
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				

			}
		}
		
	}

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

	/**
	 * Creates and returns a BleApplication message object with this peer's identifying details 
	 * @return
	 */
	private ApplicationMessage identityMessage() {
		ApplicationMessage m = new ApplicationMessage();
		m.MessageType = (byte)1 & 0xFF;
		m.SenderFingerprint = rsaKey.PuFingerprint();
		m.RecipientFingerprint = new byte[20];
		
		m.setPayload(rsaKey.PublicKey());
		
		return m;
	}
	
	private void PopulateFriends() {
		
		// let's build our friends that we've got stored up in the database
		bleFriends = new HashMap<String, BleApplicationPeer>();
		
		Cursor c = dB.fetchAllFriends();
		
		while (c.moveToNext()) {
			
			BleApplicationPeer new_peer = new BleApplicationPeer("");
			
			String peer_name = c.getString(c.getColumnIndex(FriendsDb.KEY_F_NAME));
			String peer_fp = c.getString(c.getColumnIndex(FriendsDb.KEY_F_FP));
			byte[] peer_puk = c.getBlob(c.getColumnIndex(FriendsDb.KEY_F_PUK));
			
			new_peer.SetFingerprint(peer_fp);
			new_peer.SetName(peer_name);
			new_peer.SetPublicKey(peer_puk);

			bleFriends.put(peer_fp, new_peer);
			
		}
	}
    
	/** 
	 * Given a hex representation of the public key fingerprint for a peer, search the database for any messages to be sent to this peer
	 * and return them in an ArrayList of type BleApplicationMessage.
	 * @param candidateFingerprint Public key fingerprint for peer, hexadecimal
	 * @return
	 */
	private ArrayList<ApplicationMessage> GetMessagesForFriend(String candidateFingerprint) {
		Cursor c = dB.fetchMsgsForFriend(candidateFingerprint);
		
		ArrayList<ApplicationMessage> results = new ArrayList<ApplicationMessage>();
		
		ApplicationMessage m = null; 

		// if we have any messages
		if (c.getCount() > 0) {
				//loop over these messages
				while (c.moveToNext()) {
				
				m = new ApplicationMessage();
				
				String msg_content = c.getString(c.getColumnIndex(FriendsDb.KEY_M_CONTENT));
				String msg_type = c.getString(c.getColumnIndex(FriendsDb.KEY_M_MSGTYPE));
				String msg_signature = c.getString(c.getColumnIndex(FriendsDb.KEY_M_MSGID));
				byte[] puk = c.getBlob(c.getColumnIndex(FriendsDb.KEY_F_PUK));
				
				if (msg_signature == null) {
					msg_signature = "";
				}
				
				m.RecipientFingerprint = ByteUtilities.hexToBytes(candidateFingerprint);
				m.SenderFingerprint = ByteUtilities.hexToBytes(myFingerprint);  // should probably pull from database instead; for relaying of messages
				m.ApplicationIdentifier = msg_signature;
				
				// in case we need to encrypt this message
				byte[] msgbytes = null;
				byte[] aesKeyEncrypted = null;

				// if our message is meant to be encrypted, do that first
				if (msg_type.equalsIgnoreCase("encrypted")) {
					
					SecureRandom sr = new SecureRandom();
					byte[] aeskey = new byte[32]; // 512 bit key
					sr.nextBytes(aeskey);

					byte[] iv = new byte[16];
					sr.nextBytes(iv);
					
					AESCrypt aes = null;
					
					try {
						aes = new AESCrypt(aeskey, iv);

					} catch (Exception e) {
						Log.v(TAG, "can't instantiate AESCrypt");
					}
					
					if (aes != null) {
						
						try {
							// prepend the initialization vector to the encrypted payload
							msgbytes = Bytes.concat(iv, aes.encrypt(msg_content.getBytes()));
						} catch (Exception x) {
							Log.v(TAG, "encrypt error: " + x.getMessage());
						}
						
						if (msgbytes != null) {
							// encrypt our encryption key using our recipient's public key 							
							try {
								aesKeyEncrypted = aes.encryptedSymmetricKey(puk);
								Log.v(TAG, "encrypted aes key: " + ByteUtilities.bytesToHex(aesKeyEncrypted));
							} catch (Exception e) {
								Log.v(TAG, "couldn't encrypt aes key");	
							}
						} else {
							Log.v(TAG, "couldnt encrypt message");
							break;
						}
					}
					
				} else {
					msgbytes = msg_content.getBytes();
				}
				
				if (msg_type.equalsIgnoreCase("encrypted")) {
					
					
					m.MessageType = (byte)MSGTYPE_ENCRYPTED_PAYLOAD & 0xFF;
					m.setPayload(msgbytes);
					
					ApplicationMessage m_key = new ApplicationMessage();
					
					// get the fingerprint from the Friend object
					m_key.RecipientFingerprint = m.RecipientFingerprint;
					
					// gotta give it a pre-determined messagetype to know this is an encryption key
					m_key.MessageType = (byte)MSGTYPE_ENCRYPTED_KEY & 0xFF;
					
					// get the sending fingerprint from the main message
					m_key.SenderFingerprint = m.SenderFingerprint;
					
					m_key.ApplicationIdentifier = "key_" + msg_signature;
					
					// the payload needs to include the encrypted key, and the orig msg's fingerprint
					// if the hash is a certain size, then we can assume the rest of the message is the
					// encrypted portion of the aes key
					byte[] aes_payload = Bytes.concat(m.MessageHash, aesKeyEncrypted);
					m_key.setPayload(aes_payload);
					
					Log.v(TAG, "aes key payload out: " + ByteUtilities.bytesToHex(aes_payload));
					
					results.add(m_key);
				
				} else {
					m.MessageType = (byte) MSGTYPE_PLAIN & 0xFF;
					m.setPayload(msgbytes);
				}
				
				
				
				results.add(m);
			}
			
		}
		
		return results;
		
	}
   
}
