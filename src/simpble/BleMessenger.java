package simpble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import com.google.common.primitives.Bytes;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

public class BleMessenger {
	private static String TAG = "BLEM";
	private static int INACTIVE_TIMEOUT = 600000; // 5 minute timeout
	private static int BUSINESS_TIMEOUT = 1500; // 1.5 second timeout
	
	public static final int CONNECTION_DISCONNECTED = 0;
	public static final int CONNECTION_CONNECTED = 1;
	public static final int CONNECTION_NEGOTIATING = 2;
	
	private boolean CheckAck;
	private boolean NeedSend;
	
	private Timer longTimer;
	private Timer messageStatusTimer;
	private Timer messageSendTimer;
	
	// handles to the device and system's bluetooth management 
	private BluetoothManager btMgr;
	private BluetoothAdapter btAdptr;
	
	// the context needs to be passed to the advertiser
	private Context ctx;
	
	// service base constant defined by the framework, changeable by the developer (or user)
    private static String uuidServiceBase = "73A20000-2C47-11E4-8C21-0800200C9A66";
    
    // global variables to handle Central operations and Peripheral operations
    private static BleCentral bleCentral = null; 
    private static BlePeripheral blePeripheral = null;
    
    // callback for handling events from BleCentral and BlePeripheral
    private BleStatusCallback bleStatusCallback;

    // allows us to look up peers by connected addresses
    public Map<String, BlePeer> peerMap;
    
    private Map<String, String> messageMap;
    
    // our idmessage should stay the same, so save it in a global variable
    // allow to be set from calling functions
	public BleMessage idMessage;
	
	public boolean SupportsAdvertising;
	
	private String peripheralTransport;
    
    private List<BleCharacteristic> serviceDef;
    
    private int incomingMessageCounter;
    private int outgoingMessageCounter;
    
    /**
     * Instantiates serviceDef arraylist, peerMap, fpNetMap, creates handles for peripheral/central,
     * populates serviceDef
     * 
     * @param bluetoothManager Instantiated BluetoothManager object you create
     * @param bluetoothAdapter Instantiated BluetoothAdapter object you create
     * @param context The current application's context (used by the central/peripheral functions)
     * @param BleStatusCallback Callback of type BleStatusCallback you've created
     */
	public BleMessenger(BluetoothManager bluetoothManager, BluetoothAdapter bluetoothAdapter, Context context, BleStatusCallback eventCallback) {
		
		bleStatusCallback = eventCallback;
		btMgr = bluetoothManager;
		btAdptr = bluetoothAdapter;
		ctx = context;
		CheckAck = false;
		NeedSend = false;
		
		incomingMessageCounter = 0;
		outgoingMessageCounter = 0;

		blePeripheral = null;
		
		if (Build.VERSION.SDK_INT < 21) {
			SupportsAdvertising = false;
		} else {			
			// check if we support advertising or not
			if (!btAdptr.isMultipleAdvertisementSupported()) {
				SupportsAdvertising = false;
			} else {
				SupportsAdvertising = true;			
			}
		}
		
		serviceDef = new ArrayList<BleCharacteristic>();
		
		// i need a place to put my found peers
		peerMap = new HashMap<String, BlePeer>();
		
		// when you start delivering a message, get an identifier so you can tell the calling application when you've delivered it
		messageMap = new HashMap<String, String>();
		
		// create your server for listening and your client for looking; Android can be both at the same time
		if (SupportsAdvertising) {
			blePeripheral = new BlePeripheral(uuidServiceBase, ctx, btAdptr, btMgr, peripheralHandler);
		}
		
		bleCentral = new BleCentral(btAdptr, ctx, centralHandler, uuidServiceBase, 3000, ScanSettings.SCAN_MODE_BALANCED);
				
		serviceDef.add(new BleCharacteristic("data_write", uuidFromBase("101"), BleGattCharacteristic.GATT_WRITE));
		serviceDef.add(new BleCharacteristic("data_notify", uuidFromBase("102"), BleGattCharacteristic.GATT_NOTIFY));
		serviceDef.add(new BleCharacteristic("data_indicate", uuidFromBase("103"), BleGattCharacteristic.GATT_INDICATE));
		serviceDef.add(new BleCharacteristic("data_ack", uuidFromBase("105"), BleGattCharacteristic.GATT_READWRITE));

		//serviceDef.add(new BleCharacteristic("identifier_read", uuidFromBase("100"), BleGattCharacteristics.GATT_READ));
		//serviceDef.add(new BleCharacteristic("data_write", uuidFromBase("104"), BleGattCharacteristic.GATT_WRITE));
		
		bleCentral.setRequiredServiceDef(serviceDef);
		
		//setupStaleChecker(INACTIVE_TIMEOUT);  // setup timeout
		
		peripheralTransport = "102"; // default transport to notify
	
		// when we connect, send the id message to the connecting party
	}
	
	/**
	 * Instructs your application to use either notify or indicate as the method that the peripheral writes to the central
	 * 
	 * @param Specify "notify" or "indicate"
	 * @return returns result of GetPeripheralTransportMode
	 */
	public String SetPeripheralTransportMode(String Mode) {
		
		if (Mode.equalsIgnoreCase("indicate")) {
			peripheralTransport = "103";
		} else {
			peripheralTransport = "102";
		}
				
		return GetPeripheralTransportMode();
	}
	
	/**
	 * 
	 * @return notify or indicate
	 */
	public String GetPeripheralTransportMode() {
		String result = "";
		if (peripheralTransport.equalsIgnoreCase("102")) {
			result = "notify";
		} else if (peripheralTransport.equalsIgnoreCase("103")) {
			result = "indicate";
		} else {
			peripheralTransport = "102";
			result = "notify";
		}
		
		return result;
		
	}

	
	/**
	 * Send all the messages to the passed in Peer
	 * @param Peer
	 */
	private void sendMessagesToPeer(BlePeer p) {

		// "touch" this peer so i don't send if i've talked to them recently
		p.MarkActive();
		
		// if you're connected to this peer as a peripheral and this peer isn't subscribed to anything, then exit 
		if (p.ConnectedAs.equalsIgnoreCase("peripheral") && p.subscribedChars.length() < 1) {
			Log.v(TAG, "connected as peripheral, nothing is subscribed");
			return;
		}
		
		if (p.PendingMessageCount() > 0) {
			bleStatusCallback.headsUp("m: found " + String.valueOf(p.PendingMessageCount()) + " msgs for peer " + p.RecipientAddress());
			
			// is your peer connected as a peripheral or a central?
			if (p.ConnectedAs.equalsIgnoreCase("central")) {
				// if you're a central, send as such
				Log.v(TAG, "you're a central and can initiate a send, congrats");
				
				writeOut(p.RecipientAddress());
			} else if (p.ConnectedAs.equalsIgnoreCase("peripheral")) {
				// if you're a peripheral send as such
				/* you'll need to know which attribute to write on
				 * if you're hoping to use a notify characteristic, they'll need to be subscribed to it
				*/
				// how to tell if this peer is subscribed?
				Log.v(TAG, "begin peripheral send to " + p.RecipientAddress());

				writeOut(p.RecipientAddress());
				
			}
		} else {
			Log.v(TAG, "no more messages for peer");
		}

	}
		
	public void SendMessagesToConnectedPeers() {	
		
		// loop over all the peers i have that i'm connected to	
		for (BlePeer p: peerMap.values()) {
			
			// get the first message i see
			BleMessage m = p.getBleMessageOut();
			
			// funny, we're not actually sending a particular message per se, even though we asked for a particular message
			// we're calling a method to send any pending messages for a particular peer
			// mainly because we don't store the identifier for the peer in a particular BleMessage
			// (although we could?)
			if (m != null && p.CheckStale() > 2000 && !p.IsConversing) {
				Log.v("DOIT", "pulled msg #" + String.valueOf(m.GetMessageNumber()) + ", " + ByteUtilities.bytesToHex(m.PayloadDigest).substring(0,8));
				sendMessagesToPeer(p);
				
				break;
			}
			
		}
		
		setupMessageSendTimer(BUSINESS_TIMEOUT);
	}
	
	private synchronized void setupMessageStatusTimer(long duration) {
		if (messageStatusTimer != null) {
			messageStatusTimer.cancel();
			messageStatusTimer = null;
		}
		
		if (messageStatusTimer == null && CheckAck) {
			
			messageStatusTimer = new Timer();
			
			messageStatusTimer.schedule(new TimerTask() {
				public void run() {
					messageStatusTimer.cancel();
					messageStatusTimer = null;
					
					CheckPendingMessages();
				}
				
			}, duration);
		}
	}
	
	private synchronized void setupMessageSendTimer(long duration) {
		if (messageSendTimer != null) {
			messageSendTimer.cancel();
			messageSendTimer = null;
		}
		
		if (messageSendTimer == null && NeedSend) {
			
			messageSendTimer = new Timer();
			
			messageSendTimer.schedule(new TimerTask() {
				public void run() {
					messageSendTimer.cancel();
					messageSendTimer = null;
					
					SendMessagesToConnectedPeers();
				}
				
			}, duration);
		}
	}
	
	private synchronized void setupStaleChecker(long timeout) {
		if (longTimer != null) {
			longTimer.cancel();
			longTimer = null;
		}
		
		if (longTimer == null) {
			longTimer = new Timer();
			
			longTimer.schedule(new TimerTask() {
				public void run() {
					longTimer.cancel();
					longTimer = null;
					
					// check timing on connections; drop those that are stale
					checkForStaleConnections();
				}
				
			}, timeout);
		}
	}
	
	
	private void checkForStaleConnections() {
		//bleStatusCallback.headsUp("m: check for stale connection!");
		// reset our stale-checker
		setupStaleChecker(INACTIVE_TIMEOUT);
		
		// loop over peers and check for staleness!
		for (Map.Entry<String, BlePeer> entry : peerMap.entrySet()) {
			BlePeer p = entry.getValue();
			bleStatusCallback.headsUp("m: is this guy current? connected as: " + p.ConnectedAs);
			
			// right here it's easy to disconnect a particular peripheral if you're the central
			// how to identify if the device you want to disconnect 
			if (p.CheckStale() > INACTIVE_TIMEOUT) {
				// connected to your peer as a peripheral
				if (p.ConnectedAs.equalsIgnoreCase("peripheral")) {
					blePeripheral.closeConnection(entry.getKey());
				} else {
					// connected to your peer as a central
					bleCentral.disconnectAddress(entry.getKey());
				}
				
				bleStatusCallback.headsUp("m: closing stale connection");
				peerMap.remove(entry.getKey()); // because we've disconnected, remove from our peerlist
			}
			
		}

	}
	
	/**
	 * Disconnect from a peer
	 * @param remoteAddress The Bluetooth address of the peer from whom you wish to disconnect
	 */
	public void disconnectPeer(String remoteAddress) {
		
		BlePeer p = peerMap.get(remoteAddress);
		
		// depending on how you're connected, disconnect
		if (p.ConnectedAs.equalsIgnoreCase("peripheral")) {
			blePeripheral.closeConnection(remoteAddress);
		} else {
			bleCentral.disconnectAddress(remoteAddress);
		}
		
		//peerMap.remove(remoteAddress); // because we've disconnected, remove from our peerlist
	
	}

	
	//TODO: change writeOut to indicate which particular message you're sending out
	private void writeOut(String peerAddress) {
		

		// look up the peer by their address (aka index)
		BlePeer peer = peerMap.get(peerAddress);
		
		peer.IsConversing = true;
		
		// given a peer, get the first message in the queue to send out
		BleMessage m = peer.getBleMessageOut();
		
		// add (or update) a mapping of the digest of this message to a way to get it
		messageMap.put(peerAddress + "_" + m.GetMessageNumber(), ByteUtilities.bytesToHex(m.PayloadDigest));
			
		// get a sparsearray of the packets pending send for the message m
		SparseArray<BlePacket> bps = m.GetPendingPackets();
		
		Log.v(TAG, "# of pending packets: " + String.valueOf(bps.size()));
		
		int sent = 0;
		int i = 0;
		
		bleStatusCallback.messageSendStart(peerAddress, ByteUtilities.bytesToHex(m.PayloadDigest), m.GetMessageNumber());
		
		int update_interval = (int) (bps.size() * 0.05);
		
		if (update_interval == 0) {
			update_interval = 1;
		}
		
		// loop over all our packets to send
		for (i = 0; i < bps.size(); i++) {
			
			BlePacket p = bps.valueAt(i);
			int packet_num;
			
			try {
				packet_num = bps.keyAt(i); 
			} catch (Exception x){
				packet_num = -1;
			}
			
			if (p != null) {
			
				try {
			
					Log.v(TAG, "send packet " + packet_num);
					
					byte[] nextPacket = p.MessageBytes; // packet is null here....
					boolean flag_sent = false;
					
		    		if (nextPacket != null) {
		    			if (peer.ConnectedAs.equalsIgnoreCase("central")) {
		    				Thread.sleep(100); // wait 100 ms in between sends
		    				flag_sent = bleCentral.submitCharacteristicWriteRequest(peerAddress, uuidFromBase("101"), nextPacket);
		    				Log.v(TAG, "writing packet #" + i);
		    			} else {
		    				Thread.sleep(8);
		    				flag_sent = blePeripheral.updateCharValue(peerAddress, uuidFromBase(peripheralTransport), nextPacket);
		    			}
		    		}
		    		
		    		// if the particular packet was sent, increment our "sent" counter
		    		if (flag_sent) {
		    			sent++;
		    		}
		    		
				}  catch (Exception e) {
	    			Log.e(TAG, "packet send error: " + e.getMessage());
	    			bleStatusCallback.headsUp("m: packet send error");
	    		}
				
			} else {
				Log.v(TAG, "no BlePacket for index " + i + " and packetnum " + packet_num);
				
			}
			
			// if it's time to update the calling application on the sending progress, make the callback
			if ((i % update_interval) == 0) {
				bleStatusCallback.messageStatusUpdate(peerAddress, ByteUtilities.bytesToHex(m.PayloadDigest), m.GetMessageNumber(), i);
			}
			
		}

		peer.IsConversing = false;
		
		Log.v(TAG, String.valueOf(sent) + " packets sent");
		
		// we sent packets out, so clear our pending list (we'll requeue missing later)
		m.ClearPendingPackets();

		RequestAcknowledgment(peer);
		
		
	}
	
	private String GetAddressForPeer(BlePeer p) {
		String peerAddress = "";
		
		for (Entry<String, BlePeer> entry : peerMap.entrySet()) {
			if (p.equals(entry.getValue())) {
				peerAddress = entry.getKey();
			}
		}
		
		return peerAddress;
	}
	
	private boolean RequestAcknowledgment(BlePeer p) {
		// TODO: this part about automatic acknowledgment should be set from the calling application
		boolean request_sent = false;
		
		String peerAddress = GetAddressForPeer(p);
		
		if (p.ConnectedAs.equalsIgnoreCase("central")) { 
			if (p.TransportTo) {
				bleCentral.submitCharacteristicReadRequest(peerAddress, uuidFromBase("105"));
				request_sent = true;
			}
		} else {
			if (p.TransportTo) {
				//TODO: peripheral needs to send message to central requesting acknowledgment which central will write to 105
				request_sent = true;
			}
		}
		
		// next action happens when processMessageSendAcknowledgment is called back
		
		return request_sent;
		
	}

	/**
	 * Create a new UUID using uuidServiceBase as the base; this function is more of a shortcut than anything.
	 * For instance if your uuidServiceBase is 73A20000-2C47-11E4-8C21-0800200C9A66, and you pass in "102", this function builds 73A20102-2C47-11E4-8C21-0800200C9A66 
	 * @param smallUUID A string representation of a small number
	 * @return
	 */
	private UUID uuidFromBase(String smallUUID) {
		String strUUID =  uuidServiceBase.substring(0, 4) + new String(new char[4-smallUUID.length()]).replace("\0", "0") + smallUUID + uuidServiceBase.substring(8, uuidServiceBase.length());
		UUID idUUID = UUID.fromString(strUUID);
		
		return idUUID;
	}
	
	/**
	 * Scan for ble peripherals as a central device
	 * @param duration Milliseconds to scan at a given time
	 */
	public void ScanForPeers(int duration) {
		bleStatusCallback.scanningStatusUpdate(true);
		bleCentral.setScanDuration(duration);
		bleCentral.scanForPeripherals(true);
		
	}
	
	/**
	 * Scan for ble peripherals as a central device
	 * @param duration Milliseconds to scan at a given time
	 */
	public void StopScan() {
		
		bleCentral.scanForPeripherals(false);
		
	}
		
	/**
	 * (Only works if peripheral mode is enabled)
	 * Build the service definition based on the characteristics we'd like to have, and then advertise
	 * @return
	 */
	public boolean StartAdvertising() {
		
		if (SupportsAdvertising) {		
			try {
				// pull from the service definition
				for (BleCharacteristic c: serviceDef) {
					blePeripheral.addChar(c.type, c.uuid, peripheralHandler);
				}
				
				return blePeripheral.advertiseNow();
			} catch (Exception e) {
				return false;
			}
		} else {
			return false;
		}
	}
	
	public void StopAdvertising() {
		blePeripheral.advertiseOff();
	}
	
	/**
	 * Takes the bytes of each incoming packet and assigns them to a message
	 * 
	 * @param remoteAddress The Bluetooth address of the device sending the packets to this one
	 * @param remoteCharUUID The UUID of the GATT characteristic being used for transport
	 * @param incomingBytes The raw bytes of the incoming packet
	 */
    private void incomingMessage(String remoteAddress, UUID remoteCharUUID, byte[] incomingBytes) {
		int parentMessagePacketTotal = 0;
		
		Log.v(TAG, ByteUtilities.bytesToHex(incomingBytes));
		
		// if our msg is under a few bytes it can't be valid; return
    	if (incomingBytes.length < 5) {
    		Log.v(TAG, "message bytes less than 5");
    		return;
    	}

    	// Process the header of this packet, which entails parsing out the parentMessage and which packet this is in the message
    	
    	// get the Message to which these packets belong as well as the current counter
    	int parentMessage = incomingBytes[0] & 0xFF; //00
    	int packetCounter = (incomingBytes[1] & 0xFF) << 8 | incomingBytes[2] & 0xFF; //0001

    	// get the peer which matches the connected remote address 
    	BlePeer p = peerMap.get(remoteAddress);
    	
    	// update "last heard from" time
    	p.MarkActive();
    	
    	// find the message we're building, identified by the first byte (cast to an integer 0-255)
    	// if this message wasn't already created, then the getBleMessageIn method will create it
    	BleMessage msgBeingBuilt = p.getBleMessageIn(parentMessage);
    	
    	// your packet payload will be the size of the incoming bytes less our 3 needed for the header
    	byte[] packetPayload = Arrays.copyOfRange(incomingBytes, 3, incomingBytes.length);
    	
    	
    	// if our current packet counter is ZERO, then we can expect our payload to be:
    	// the number of packets we're expecting
    	if (packetCounter == 0) {
    		if (p.ConnectedAs.equalsIgnoreCase("central")) {
    			incomingMessageCounter++;
    			CheckAck = true;
    			setupMessageStatusTimer(BUSINESS_TIMEOUT); // as a central, periodically check on pending messages
    		}
    		
    		// get the number of packets we're expecting - 2 bytes can indicate 0 through 65,535 packets 
    		parentMessagePacketTotal = (packetPayload[0] & 0xFF) << 8 | packetPayload[1] & 0xFF;
    		
    		// since this is the first packet in the message, pass in the number of packets we're expecting
    		msgBeingBuilt.BuildMessageFromPackets(packetCounter, packetPayload, parentMessagePacketTotal);
    	} else {
    		
    		// otherwise throw this packet payload into the message
    		msgBeingBuilt.BuildMessageFromPackets(packetCounter, packetPayload);	
    	}
    	
    	// If all the expected packets have been received, process this message
    	if (msgBeingBuilt.PendingPacketStatus() == false) {
    		
    		// so now we need to stop check on pending messages if there are no pending messages
    		
    		// if, as a Central, we've received all the packets then go ahead and let the sending peripheral know
    		// if you're a Peripheral, you will wait for the Central to let you know what is missing
    		if (p.ConnectedAs.equalsIgnoreCase("central")) {
    			
    			// msg received, decrement counter
    			incomingMessageCounter--;
    			
    			if (incomingMessageCounter <= 0) {
    				CheckAck = false;
    			}
    			
    			Log.v(TAG, "parentMessage # is:" + parentMessage);
    			
    			// what message are we talking about?
    			byte[] ACKet = new byte[] {(byte)parentMessage};
    			
    			// create an acknowledgment packet with only 0's, indicating we got it all
    			byte[] ack = new byte[20];
    			ack = Arrays.copyOf(ACKet, ack.length);
    			
    			bleCentral.submitCharacteristicWriteRequest(remoteAddress, uuidFromBase("105"), ack);
    			
    		}
    		
    		boolean msgIntact = msgBeingBuilt.VerifyDigest();
    		// TODO: do we need to have a hash check?
    		bleStatusCallback.handleReceivedMessage(remoteAddress, parentMessage, msgIntact, msgBeingBuilt.GetAllBytes());
    		
    		

    	} else {
    		// if this is the first packet, notify the calling activity
    		if (packetCounter == 0) {
    			
    			byte[] packetHash = Arrays.copyOfRange(packetPayload, 2, packetPayload.length);
    			String messageHash = ByteUtilities.bytesToHex(packetHash);
    			
    			bleStatusCallback.newMessage(remoteAddress, messageHash, parentMessage, parentMessagePacketTotal);

    		} else { // this is too much to callback, hurts throughput
    			int update_interval = (int) (msgBeingBuilt.ExpectedPacketCount() * 0.05);
    			
    			if (update_interval == 0) {
    				update_interval = 1;
    			}
    			
    			if ((packetCounter % update_interval) == 0) {
    				bleStatusCallback.incomingPacket(remoteAddress, "", msgBeingBuilt.GetMessageNumber(), packetCounter);
    			}
    			
    		}
    		
    	}
    	
		
    }
    
    /**
     * A peer sent me some packets requesting that I acknowledge a packet
     * 
     * @param remoteAddress
     * @param remoteCharUUID
     * @param incomingBytes
     */
    private void processMessageSendAcknowledgment(String remoteAddress, UUID remoteCharUUID, byte[] incomingBytes) {
    	// get the remote peer based on the address
    	
    	BlePeer p = peerMap.get(remoteAddress);
    	
    	if (p==null) {
    		bleStatusCallback.headsUp("m: no peer found for address " + remoteAddress);
    		return;
    	}
    	
    	// get the message ID to check on
    	int msg_id = incomingBytes[0] & 0xFF;
    	
    	bleStatusCallback.headsUp("m: hearing from " + remoteAddress + " re msg#" + String.valueOf(msg_id));
    	
    	// instantiate a message object
    	BleMessage m = null;
    	
    	// get all the outbound messages in an array
    	SparseArray<BleMessage> blms = p.GetMessagesOut();
    	
    	// get the message corresponding to this msgid
    	m = blms.get(msg_id);
    	
		if (m != null) {
			
			String payloadDigest = messageMap.get(remoteAddress + "_" + msg_id);
			
			bleStatusCallback.headsUp("m: bleMessage found at index " + String.valueOf(msg_id));

			// how many missing packets?  if 0, we're all set; call it done
	    	int missing_packet_count = incomingBytes[1] & 0xFF << 8 | (incomingBytes[2] & 0xFF);
	    	Log.v(TAG, "missing packet count:" + missing_packet_count);
	    	
	    	// if we're all done, mark this message sent
	    	if (missing_packet_count == 0) {
	    		Log.v(TAG, "all sent, removing msg " + String.valueOf(msg_id) + " from queue");

	    		bleStatusCallback.messageDelivered(remoteAddress, payloadDigest, msg_id);
	    		p.RemoveBleMessage(msg_id);
	    		
	    		outgoingMessageCounter--;
	    		
	    		if (outgoingMessageCounter <= 0) {
	    			NeedSend = false;
	    		}
	    		
	    	} else {
	    		Log.v(TAG, "msg " + String.valueOf(msg_id) + " not sent, checking missing packets");
	    		
	    		// read the missing packet numbers into an array
	    		byte[] missingPackets = Arrays.copyOfRange(incomingBytes, 3, incomingBytes.length);

	    		Log.v(TAG, "missing packets bytes: " + ByteUtilities.bytesToHex(missingPackets));
	    		
	    		SparseIntArray s = new SparseIntArray();
	    		int packet_requeue_count = 0;
	    		// at most we can have 5 triplets of ranges
	    		for (int i = 0; i < 5; i++) {
	    			int tripletStart = i * 3;
	    			
		    		int missingOffset = (missingPackets[tripletStart] & 0xFF) << 8 | (missingPackets[tripletStart+1] & 0xFF);
		    		
		    		int missingCount = (byte)missingPackets[tripletStart+2] & 0xFF;
		    		
		    		Log.v(TAG, "from packet " + missingOffset + " queue " + missingCount);
		    		
		    		packet_requeue_count = packet_requeue_count + missingCount;
		    		
		    		if (missingOffset > 0) {
		    			s.put(missingOffset, missingCount);
		    		}
	    			
	    		}

	    		
	    		m.PacketReQueue(s);
	    		

	    		bleStatusCallback.packetsRequeued(remoteAddress, msg_id, packet_requeue_count);
	    		
	    		Log.v(TAG, "message now has " + String.valueOf(m.GetPendingPackets().size()) + " packets to send");
	    		
	    		// flag these packets for re-send
	    		
	    	}
    	
    	} else {
    		bleStatusCallback.headsUp("m: p.getBleMessageOut(" + String.valueOf(msg_id) + ") doesn't pull up a blemessage");
    	}
    }
    
    
    BlePeripheralHandler peripheralHandler = new BlePeripheralHandler() {
    	/**
    	 * The Gatt Peripheral handler provides an update for the connection state; here we handle that
    	 * If we're connected
    	 */
    	public void ConnectionState(String remoteAddress, int status, int newStatus) {

    		BlePeer p = new BlePeer(remoteAddress);
    		
    		// if connected
    		if (newStatus == BluetoothProfile.STATE_CONNECTED) {
	    		
	    		 // create a new peer to hold messages and such for this network device
	    		p.ConnectedAs = "peripheral";
	    		
	    		peerMap.put(remoteAddress, p);
	    		
	    		// if we've been connected to, we can assume the central can write out to us
	    		p.TransportFrom = true;
	    		// we can't set TransportTo, because we haven't been subscribed to yet
	    		
	    		// the peer hasn't subscribed yet, so we're not fully connected
	    		bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_NEGOTIATING);

    		} else {
	    		// let the calling activity know that as a peripheral, we've lost or connection
    			peerMap.remove(remoteAddress);
    			bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_DISCONNECTED);
    			p.TransportFrom = false;
    			p.TransportTo = false;
    		}
    		
    	}

    	public void incomingMissive(String remoteAddress, UUID remoteCharUUID, byte[] incomingBytes) {
    		// based on remoteAddress, UUID of remote characteristic, put the incomingBytes into a Message
    		
    		if (remoteCharUUID.compareTo(uuidFromBase("105")) == 0) {
    			processMessageSendAcknowledgment(remoteAddress, remoteCharUUID, incomingBytes);    			
    		} else  {
    			incomingMessage(remoteAddress, remoteCharUUID, incomingBytes);	
    		}
    			
    	}

		@Override
		public void handleNotifyRequest(String remoteAddress, UUID uuid) {
    		// we're connected, so initiate send to "device", to whom we're already connected
    		Log.v(TAG, "from handleNotifyRequest, initiate sending messages");
    		
    		// we've got a notify request, so let's reset this peer's inactivity timeout
    		//bleStatusCallback.headsUp("m: notify request from " + device + "; reset timeout");
    		
    		bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_CONNECTED);
    		
    		BlePeer p = peerMap.get(remoteAddress);
    		p.MarkActive();
    		p.subscribedChars = uuid.toString() + ";" + p.subscribedChars;
    		p.TransportTo = true;
    		
		}

		@Override
		public void handleAdvertiseChange(boolean advertising) {
			bleStatusCallback.advertisingStatusUpdate(advertising);
		}

		@Override
		public void prepReadCharacteristic(String remoteAddress, UUID remoteCharUUID) {
			// figure out whom i'm talking to based on remoteAddress
			// BT 4.1 allows multiple centrals, so you need to keep remoteAddress to tell which one
			
			// if somebody's hitting 105 they're gonna wanna know if their msg is sent or not
			if (remoteCharUUID.toString().equalsIgnoreCase(uuidFromBase("105").toString())) {
				
				byte[] missingPackets = missingPacketsForPeer(remoteAddress);
				
				Log.v(TAG, "missing packet delivery: " + ByteUtilities.bytesToHex(missingPackets));
				
				// if we still need packets
				blePeripheral.updateCharValue(remoteAddress, remoteCharUUID, missingPackets);
				
			}
		}
    	
    };


    BleCentralHandler centralHandler = new BleCentralHandler() {
    	
    	@Override
    	public void subscribeSuccess(String remoteAddress, UUID remoteCharUUID) {
    		
    		// we've subscribed, so we can consider ourselves connected
    		bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_CONNECTED);
    		
    		BlePeer p  = peerMap.get(remoteAddress);
    		p.TransportFrom = true;
    		
    	}
    	
    	@Override
    	public void incomingMissive(String remoteAddress, UUID remoteCharUUID, byte[] incomingBytes) {
    		
    		// if the UUID is 102, it's the notify
    		if (remoteCharUUID.compareTo(uuidFromBase(peripheralTransport)) == 0) {
    			incomingMessage(remoteAddress, remoteCharUUID, incomingBytes);    			
    		} else if (remoteCharUUID.compareTo(uuidFromBase("105")) == 0) {
    			processMessageSendAcknowledgment(remoteAddress, remoteCharUUID, incomingBytes);
    		}
    		
    	}
    	
		public void intakeFoundDevices(ArrayList<BluetoothDevice> devices) {

			// your scanning is done
			bleStatusCallback.scanningStatusUpdate(false);
			
			// loop over all the found devices
			// add them 
			for (BluetoothDevice b: devices) {
				Log.v(TAG, "(BleMessenger)MyGattClientHandler found device:" + b.getAddress());
				
				
				// if the peer isn't already in our list, put them in!
				String peerAddress = b.getAddress();
				
				if (!peerMap.containsKey(peerAddress)) {
					BlePeer blePeer = new BlePeer(peerAddress);
					blePeer.ConnectedAs = "central";
					peerMap.put(peerAddress, blePeer);
				}
				
				// initiate a connection to the remote address, which checks to see if it's a valid peer
				// if it's not, then you won't be connected
				bleCentral.connectAddress(peerAddress);
				
			}
			
		}
		
		@Override
		public void connectedServiceGood(String remoteAddress) {

    		// we've subscribed, so we can consider ourselves connected
    		bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_NEGOTIATING);
			
			// get a handle to this peer
			BlePeer p = peerMap.get(remoteAddress);
			
			// reset our timeout timer
			p.MarkActive();
			
			// as a Central, when we know the service is good, we know we can send msgs to this peer
			p.TransportTo = true;
			
			// now let's subscribe so we can get inbound stuff
			
			// onDescriptorWrite callback will be called, which will call blecentralhandler's subscribeSuccess
			bleCentral.submitSubscription(remoteAddress, uuidFromBase(peripheralTransport));
			
		}
		
		@Override
		public void reportDisconnect(String remoteAddress) {
			// report disconnection to MainActivity
			peerMap.remove(remoteAddress);
			bleStatusCallback.peerConnectionStatus(remoteAddress, CONNECTION_DISCONNECTED);
		}
    	
    };
    
    /**
     * Add a message to the outgoing queue for a connected peer
     * 
     * @param remoteAddress The identifier for the target connectee; this is how SimpBle identifies the recipient
     * @param msg A BleApplicationMessage object with all the right stuff
     * @return A digest of the message's payload
     */
    public String AddMessage(String remoteAddress, BleApplicationMessage msg) {
    	String result = "";

		// pass your message's bytes to the peer object to build a BleMessage to send, and get a digest of that message
		result = AddMessage(remoteAddress, msg.GetAllBytes());
    	
    	return result;
    	
    }
    
    /**
     * Add a message to the outgoing queue for a connected peer
     * 
     * @param remoteAddress The identifier for the target connectee; this is how SimpBle identifies the recipient
     * @param messageBytes Raw bytes to send to the connected peer; it's smarter to use your own BleApplicationMessage object
     * @return A digest of the message's payload
     */
    public String AddMessage(String remoteAddress, byte[] messageBytes) {
    	String result = "";
		BlePeer p = peerMap.get(remoteAddress);
		
		// we've added an outgoing message; we've got at least this one to send 
		outgoingMessageCounter++;
		NeedSend = true;
		
		// if we don't find a friend with this address, just return null
		if (p == null) {
			return null;
		}
		// pass your message's bytes to the peer object to build a BleMessage to send, and get a digest of that message
		result = p.BuildBleMessageOut(messageBytes);
		
		if (outgoingMessageCounter >= 1){
			setupMessageSendTimer(BUSINESS_TIMEOUT);
		}
    	
    	return result;
    	
    }

    /**
     *	If you're a central and you started getting a message and the peripheral just stopped sending it,
     *	you'll need to check the last time you received a packet for that message; if it's later than you'd
     *	like you'll need to send an ACK packet to the Peripheral letting them know you're expecting more from them;
     * this should only happen with Notify packets
     */
    private synchronized void CheckPendingMessages() {
    	
    	BlePeer p = null;
    	boolean messageTimerSet = false;
    	
    	for (Map.Entry<String, BlePeer> entry : peerMap.entrySet()) {
    		String peerAddress = entry.getKey();
    		p = entry.getValue();

    		// only go through this rigamarole if you're connected as a central
    		if (p.ConnectedAs.equalsIgnoreCase("central") && p.CheckStale() > 1000) {
    		
				byte[] ack = missingPacketsForPeer(peerAddress);
				
				
				if (ack != null) {
					
					int msgid = ack[0];
					int missing_total = (ack[1] & 0xFF) << 8 | ack[2] & 0xFF;
					
					Log.v("CHECK", "missing packets delivery:" + peerAddress + ", " + ByteUtilities.bytesToHex(ack));
					
					bleCentral.submitCharacteristicWriteRequest(peerAddress, uuidFromBase("105"), ack);
					bleStatusCallback.missingPackets(peerAddress, msgid, missing_total);
					
					// tell the remote person about this missing stuff, and then requeue
					setupMessageStatusTimer(BUSINESS_TIMEOUT + p.CheckStale());
					messageTimerSet = true;
					break;
				}
    		} 
			
    	}
    	
    	if (!messageTimerSet) {
    		setupMessageStatusTimer(BUSINESS_TIMEOUT);
    	}

		// in case you don't have any missing packets, still check again
    	/*
    	if (p != null) {
    		if (p.ConnectedAs.equalsIgnoreCase("central")) {
    			setupMessageStatusTimer(BUSINESS_TIMEOUT);
    		}
    	}*/
    }
    
    /**
     * 
     * @param remoteAddress
     * @return
     */
    private byte[] missingPacketsForPeer(String remoteAddress) {
    	
    	Log.v(TAG, "check missing packets for peer " + remoteAddress);
    	
    	byte[] missingPackets = null;

		// get the peer who just asked us if we have any incomplete messages
		BlePeer p = peerMap.get(remoteAddress);
	
		// loop over the inbound message numbers (even though we're only doing the first)
		for (int k: p.GetMessageIn().keySet()) {	
		
			// get the first message
			BleMessage m = p.getBleMessageIn(k);

			if (!m.ReceiptAcknowledged) {
				
				// see if we've got any missing packets, this returns a range; (0-65535 start, 0-255 offset)
				SparseIntArray missing = m.GetMissingPackets();
			
				Log.v(TAG, "# of missing ranges: " + missing.size());
				
				// create a byte array which will be our "we lack these" message
				missingPackets = new byte[1];

				// first byte will be message identifier
				missingPackets[0] = Integer.valueOf(k).byteValue();
				
				if (missing.size() == 0) {
					m.ReceiptAcknowledged = true;
					missingPackets = Arrays.copyOf(missingPackets, 20);
					break;
				}
				
				missingPackets = new byte[3];
				missingPackets[0] = Integer.valueOf(k).byteValue();
			
				int totalMissing = 0;
				
				for (int i = 0; (i < missing.size()) && i < 5; i++) {
					totalMissing = totalMissing + missing.valueAt(i) + 1;
					int offsetMissing = missing.keyAt(i);
					
					Log.v(TAG, "offset missing for index " + i + " is " + offsetMissing);
					
					byte[] missing_start = new byte[2];
					
					if (offsetMissing <= 255) {
						missing_start[0] = (byte)0x00;
						missing_start[1] = (byte)offsetMissing;
					} else {
						missing_start = ByteUtilities.intToByte(offsetMissing);
					}
					
					byte[] trio = new byte[] { (byte)missing_start[0], (byte)missing_start[1], (byte)missing.valueAt(i) };
					Log.v(TAG, "trio " + i + " is " + ByteUtilities.bytesToHex(trio));
					
					missingPackets = Bytes.concat(missingPackets, trio);
					
				}
				
				// second two bytes will be number of missing packets
				byte[] missingCount = ByteUtilities.intToByte(totalMissing);
				
				if (missingCount.length == 1) {
					missingPackets[1] = (byte)0x00;
					missingPackets[2] = missingCount[0];
				} else {
					missingPackets[1] = missingCount[0];
					missingPackets[2] = missingCount[1];
				}
			
				missingPackets = Arrays.copyOf(missingPackets, 20);
				break;
			}
		
		}
		
		return missingPackets;
    }

    
}
