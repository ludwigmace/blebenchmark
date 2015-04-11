package simpble;

public interface BleStatusCallback {
	
	/**
	 * Callback executed when your peer receives a message 
	 * 
	 * @param remoteAddress Bluetooth address of the remote peer, only useful as an index to identify your peer
	 * @param MessageBytes Raw byte payload of the message that you must now operate on
	 */
	public void handleReceivedMessage(String remoteAddress, byte[] MessageBytes);
	
	/**
	 * If your client is in Peripheral mode, this notifies your application that your Advertising status has changed; ie, are you visible to scanning Centrals or not?
	 * 
	 * @param isAdvertising
	 */
	public void advertisingStatusUpdate(boolean isAdvertising);
	
	/**
	 * Callback executed when a message you have queued has been delivered.  Between the remoteAddress and digest you should be able to uniquely identify your message 
	 * 
	 * @param remoteAddress Bluetooth address (just used as an index, could be anything) of the peer to whom BleMessenger delivered the message
	 * @param payloadDigest Hex digest of the delivered message
	 */
	public void messageDelivered(String remoteAddress, String payloadDigest);
	
	/**
	 * Callback executed when connection (Bluetooth or messenger) status has changed
	 * 
	 * @param remoteAddress Bluetooth address (just used as an index, could be anything) of the now-disconnected peer
	 * @param ConnectionStatus 	CONNECTION_DISCONNECTED, CONNECTION_CONNECTED, CONNECTION_NEGOTIATING
	 */
	public void peerConnectionStatus(String remoteAddress, int ConnectionStatus);
		
	/**
	 * Callback executed when the first packet of a new message is being sent from a peer
	 * 
	 * @param remoteAddress Bluetooth address (just used as an index, could be anything) of the now-disconnected peer
	 * @param messageId a truncated hash of the incoming message
	 * @param packetsExpected number of packets we should end up receiving
	 */
	public void newMessage(String remoteAddress, String messageHash, int messageId, int packetsExpected);
	

	/**
	 * Callback executed when a non-initial packet is coming in
	 * 
	 * @param remoteAddress Bluetooth address (just used as an index, could be anything) of the now-disconnected peer
	 * @param messageId a truncated hash of the incoming message
	 * @param packetsExpected number of packets we should end up receiving
	 */
	public void incomingPacket(String remoteAddress, int messageId, int currentPacket);
	
	
	public void packetsRequeued(String remoteAddress, int messageId, int missingPacketCount);
	
	
	/**
	 * You can use this for debugging; for example in the implementation of the callback you can show messages in popups or otherwise 
	 * @param msg
	 */
	public void headsUp(String msg);
	
}
