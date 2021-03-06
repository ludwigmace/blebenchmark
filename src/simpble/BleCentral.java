package simpble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

public class BleCentral extends BleCentralShared {
	
	private static final String TAG = "BLEC";
	private static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
	
	// we need the system context to perform Gatt operations
	private Context ctx;

	// acts as a base from which to calculate characteristic uuid's
    private String strSvcUuidBase;
  
	// scanning happens asynchronously, so get a link to a Handler
    private Handler mHandler;
    
    // used to determin if we're currently scanning
    private boolean mScanning;
    
    // keeps a list of devices we found during the scan
    private ArrayList<BluetoothDevice> foundDevices;
    
    // a hook to our bluetooth adapter
    private BluetoothAdapter centralBTA;
    
    // allows external actors to handle events generated by our gatt operations
    private BleCentralHandler bleCentralHandler;
    
    // our service definition
    private List<BleCharacteristic> serviceDef;
    
    // bluetooth gatt functionality pointed to a particular remote server
    private Map<String, BluetoothGatt> gattS;
    
    // bluetooth gatt functionality pointed to a particular remote server
    private Map<String, BluetoothDevice> devicesByAddress;
    
    // scan duration
    private long scanDuration;

    // scanning object
    private BluetoothLeScanner bleScanner;
    
    // scan settings
    private ScanSettings bleScanSettings;
    
    // scan filter
    private List<ScanFilter> bleScanFilter;
    
    private Map<String, Integer> mtuMap;
    
    /**
     * A helper class for dealing with Bluetooth Central operations
     * @param btA system bluetooth adapter
     * @param ctx system context
     * @param myHandler callback to handle events generated by this class
     * @param serviceUuidBase base uuid for calculating other characteristic uuid's
     * @param defaultScanInMs milliseconds to scan when given the scan command
     * @param ScanMode - use one of these: ScanSettings.SCAN_MODE_BALANCED; ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_POWER
     */
    BleCentral(BluetoothAdapter btA, Context ctx, BleCentralHandler myHandler, String serviceUuidBase, long defaultScanInMs, int ScanMode) {
    	
    	Log.v(TAG, "central instantiated");
    	
    	scanDuration = defaultScanInMs;
    	
    	centralBTA = btA;
    	
    	boolean validUuidBase = false;
    	
    	// check if the service base passed in is valid; if there's an error then use the default service base
    	try {
    		UUID.fromString(serviceUuidBase);
    		validUuidBase = true;
    	} catch (Exception e) {
    		validUuidBase = false;
    	}
    	
    	if (validUuidBase) {
    		strSvcUuidBase = serviceUuidBase;    		
    	} else {
    		strSvcUuidBase = "73A20000-2C47-11E4-8C21-0800200C9A66";
    	}
    	
    	this.ctx = ctx;
    
    	// to be used for scanning for LE devices
        mHandler = new Handler();
        
        foundDevices = new ArrayList<BluetoothDevice>(); 
        
        mScanning = false;
        
        bleCentralHandler = myHandler;
        
        serviceDef = new ArrayList<BleCharacteristic>();
        
        gattS = new HashMap<String, BluetoothGatt>();
        mtuMap = new HashMap<String, Integer>();
        
        
        ScanSettings.Builder sb = new ScanSettings.Builder();
        sb.setReportDelay(0); // report results immediately
        sb.setScanMode(ScanMode); // options are: ScanSettings.SCAN_MODE_BALANCED; ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_LOW_POWER;
        bleScanSettings = sb.build();
        
        // only report devices that contain our desired service uuid
        ScanFilter.Builder sf = new ScanFilter.Builder();
        sf.setServiceUuid(ParcelUuid.fromString(strSvcUuidBase));
        bleScanFilter = new ArrayList<ScanFilter>();
        bleScanFilter.add(sf.build());
        
    }
    
    /**
     * Set the duration for your scan, in MS
     * @param duration Millisecond duration for which you'd like to scan
     */
    public void setScanDuration(int duration) {
    	scanDuration = duration;
    }


    /**
     * Connect to a peer that you probably found by scanning
     * @param btAddress Bluetooth address of a potential peer this central client will try to connect to
     */
    public void connectAddress(String btAddress){
    	Log.v(TAG, "connect to " + btAddress);
    	BluetoothDevice b = centralBTA.getRemoteDevice(btAddress);
    	b.connectGatt(ctx, false, mGattCallback);
    }
    
    public void requestMtu(String btAddress) {
    	gattS.get(btAddress).requestMtu(512);
    }
    
    /**
     * Disconnect from a peer specified by btAddress
     * @param btAddress Bluetooth address as a string
     */
    public void disconnectAddress(String btAddress) {
    	// get the gatt connection to the particular server and disconnect
    	try {
    		gattS.get(btAddress).disconnect();
    		gattS.get(btAddress).close();
    	} catch (Exception e) {
    		Log.e(TAG, "error disconnecting");
    		Log.e(TAG, e.getMessage());
    	}

    }
    
    /**
     * Sets the service definition (ie, characteristics offered) that the peripheral must meet in order for this client to connect to it
     * 
     * @param bleChars List of BleCharacteristics
     */
    public void setRequiredServiceDef(List<BleCharacteristic> bleChars) {
    	serviceDef = bleChars;
    }
    
    /**
     * Subscribe to a peripheral's Indicate or Notify characteristic
     * @param remoteAddr Bluetooth address of the peripheral
     * @param uuidChar UUID of the characteristic to be subscribed
     * @return boolean - Well did it work?
     */
    public boolean submitSubscription(String remoteAddr, UUID uuidChar) {
    	boolean result = false;
    	
    	BluetoothGattCharacteristic indicifyChar = null;
    	BluetoothGatt gatt = null;
    	
    	try {
    		// since you can feasibly access multiple peripherals i keep my gatt server objects in a map, indexed by address
    		gatt = gattS.get(remoteAddr);
    		indicifyChar = gatt.getService(UUID.fromString(strSvcUuidBase)).getCharacteristic(uuidChar);
    	} catch (Exception x) {
    		Log.v(TAG, "can't subscribe: " + x.getMessage());
    		return false;
    	}
   	
    	if (indicifyChar != null) {

        	int cProps = indicifyChar.getProperties();

        	// subbing for notify or indicate is the same deal
	   	     if ((cProps & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
		    	 Log.v(TAG, "sub for notifications from " + indicifyChar.getUuid().toString().substring(0,8));
			
				// enable notifications/indications for this guy
		    	gatt.setCharacteristicNotification(indicifyChar, true);
				
				// tell the other guy that we want characteristics enabled
				BluetoothGattDescriptor descriptor = indicifyChar.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));
				
				// if it's a notification value, subscribe by setting the descriptor to ENABLE_NOTIFICATION_VALUE
				if ((cProps & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
					descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				}

				// if it's an INDICATION value, subscribe by setting the descriptor to ENABLE_INDICATION_VALUE				
				if ((cProps & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
					descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
				}
				
				gatt.writeDescriptor(descriptor);
				
				result = true;
			
			}
    	} else {
    		Log.v(TAG, "can't pull characteristic so i can't sub it");
    	}
    	    	
		return result;
    }
        
    /**
     * Is the Central client current scanning?
     * @return boolean True if currently scanning, False if not
     */
    public boolean isScanning() {
    	return mScanning;
    }
    
    /**
     * Initiate a read to a remote characteristic; the result ends up in the callback - BluetoothGattCallback.onCharacteristicRead method
     * @param remoteAddr Bluetooth address of the peripheral
     * @param uuidChar UUID of the characteristic to read
     * @return
     */
    public boolean submitCharacteristicReadRequest(String remoteAddr, UUID uuidChar) {
    	
    	boolean charFound = false;
    	
    	BluetoothGatt gatt = gattS.get(remoteAddr);
    	BluetoothGattCharacteristic readChar = null;
    	
    	try {
    		readChar = gatt.getService(UUID.fromString(strSvcUuidBase)).getCharacteristic(uuidChar);
    	} catch (Exception e) {
    		Log.v(TAG, "failed to get read characteristic from service for uuidChar " + uuidChar.toString());
    	}

    	if (readChar != null) {
    		Log.v(TAG, "issuing read request:" + readChar.getUuid().toString());
    		gatt.readCharacteristic(readChar);
    		charFound = true;
    	} else {
    		
    	}
    	
    	return charFound;
    	
    }
    
    /**
     * Write some data to a peripheral's "write"-enabled characteristic; BluetoothGattCallback.onCharacteristicWrite is called with a success or failure
     * 
     * @param remoteAddr Bluetooth address of the target peripheral
     * @param uuidChar UUID of the characteristic
     * @param val byte array of the data
     * @return
     */
    public boolean submitCharacteristicWriteRequest(String remoteAddr, UUID uuidChar, final byte[] val) {
		
    	boolean charWrote = false;

    	BluetoothGatt gatt = null;
    	BluetoothGattCharacteristic writeChar = null;
    	
    	try {
    		gatt = gattS.get(remoteAddr);
    		writeChar = gatt.getService(UUID.fromString(strSvcUuidBase)).getCharacteristic(uuidChar);
    	} catch (Exception e) {
    		Log.v(TAG, "central write err: " + e.getMessage());
    		return false;
    	}
    	
    	writeChar.setValue(val);
    	writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
    		
    	try {
    		gatt.writeCharacteristic(writeChar);
    		charWrote = true;
    	} catch (Exception e) {
    		Log.v(TAG, "cannot write char ");
    		Log.v(TAG, e.getMessage());
    	}
		
		return charWrote;
    }
    
    /**
     * This is implemented here pretty much to deal with scan results
     * TODO: deal with failed scans, check into batch scans
     */
    private ScanCallback scanCallback = new ScanCallback() {

    	public void onBatchScanResults(List<ScanResult> results) {
    		
    	}
    	
    	public void onScanFailed (int errorCode) {
    		
    	}
    	
    	public void onScanResult (int callbackType, ScanResult result) {
    		
    		Log.v(TAG, "found a device " + result.getDevice());
    		// if we haven't already gotten the device, then add it to our list of found devices
			if (!foundDevices.contains(result.getDevice())) {
				foundDevices.add(result.getDevice());
	    	}
    	}
    	
    };
    

    /**
     * Initiate a scan for peripheral devices - or stop a scan.
     * @param enable Boolean will pretty much always be true, but if you wanna stop an ongoing scan then pass in False
     */
    public void scanForPeripherals(final boolean enable) {
    	final long SCAN_PERIOD = scanDuration;
    	
        if (enable) {
        	if (bleScanner == null) {
        		bleScanner = centralBTA.getBluetoothLeScanner();
        	}

        	// call STOP after SCAN_PERIOD ms, which will spawn a thread to stop the scan
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    
                    if (bleScanner != null) {
                    	bleScanner.stopScan(scanCallback);
                    }

        			bleCentralHandler.intakeFoundDevices(foundDevices);
        			Log.v(TAG, "stopscan, " + String.valueOf(foundDevices.size()) + " devices, thread "+ Thread.currentThread().getName());
        			bleScanner = null;
                }
            }, SCAN_PERIOD);

            // start scanning!
            mScanning = true;
            
            Log.v(TAG, "scan started");

            bleScanner.startScan(bleScanFilter, bleScanSettings, scanCallback);

        } else {
        	
        	// the "enable" variable passed was False, so turn scanning off
            mScanning = false;
            if (bleScanner != null) {
            	bleScanner.stopScan(scanCallback);
            }
            bleScanner = null;
            
        }

    }


	/**
     * This callback is provided by Android to handle Gatt stuff.
     * 
     * onCharacteristicChanged - calls BleCentralHandler.incomingMissive in BleMessenger when receiving data from an Indicate or Notify characteristic
     * onDescriptorWrite - we only use for characteristic subscription; BleCentralHandler.subscribeSuccess is called when subscription is successful
     * onConnectionStateChange - if we connect or disconnect, this is called; adds/removes from our list of Gatt servers
     * onCharacteristicWrite - callback after a characteristic write; we should probably have it do something if it fails (well, up to a threshold)
     * onServicesDiscovered - we use this to see if our service definition is met; BleCentralHandler.connectedServiceGood is called if it's met, we disconnect if not (calling activity doesn't need to know)
     * onCharacteristicRead - if able to read a characteristic, pass the data to BleCentralHandler.incomingMissive; if not, we need to do something (not doing anything now) 
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
    	@Override
    	// Characteristic notification
    	public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
    		bleCentralHandler.incomingMissive(gatt.getDevice().getAddress(), characteristic.getUuid(), characteristic.getValue());
    		
    	}
    	
    	@Override
    	public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
    		String remote = gatt.getDevice().getAddress();
    		
    		int finalMtu = 20;
    		
    		if (status == BluetoothGatt.GATT_SUCCESS) {
    			finalMtu = mtu;
    		}
    		
    		mtuMap.put(remote, finalMtu);
    		
    	}
    	
    	@Override
    	public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

    		// if this is a subscription write
    		if (descriptor.getUuid().compareTo(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG)) == 0 && status == BluetoothGatt.GATT_SUCCESS) {
    			// get the server that reported we successfully wrote to this characteristic
    			String remote = gatt.getDevice().getAddress();
    			
        		// get the characteristic to which this descriptor is associated
        		BluetoothGattCharacteristic c = descriptor.getCharacteristic();
        		
        		bleCentralHandler.subscribeSuccess(remote, c.getUuid());	
        		
    		}
    	}
    	    	
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

        	Log.v(TAG, "connection state changed for " + gatt.getDevice().getAddress() + " to " + newState);
        	
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                		
                Log.v(TAG, "Connected to GATT server " + gatt.getDevice().getAddress());

                // save a reference to this gatt server!
                gattS.put(gatt.getDevice().getAddress(), gatt);
                
                gatt.discoverServices();
                //Log.i(TAG, "Attempting to start service discovery:" + mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

            	String remoteAddress = gatt.getDevice().getAddress();
            	
            	// try to disconnect
                try {
                	gattS.get(remoteAddress).disconnect();
                } catch (Exception x) {
                	
                }
                
                // try to close the gatt reference
                try {
                    gattS.get(remoteAddress).close();                	
                } catch (Exception x) {
                	
                }
                
                // since we're disconnected, remove this guy
                gattS.remove(remoteAddress);
                
                // try to close local references
                try {
                	gatt.disconnect();
                	gatt.close();
                } catch (Exception x) {
                	
                }
                
                bleCentralHandler.reportDisconnect(remoteAddress);
                Log.i(TAG, "Disconnected from GATT server " + gatt.getDevice().getAddress());
                
            }
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        	
        }

        @Override
        // New services discovered
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        	Log.v(TAG, "looking for services " + gatt.getDevice().getAddress() + " on thread " + Thread.currentThread().getName());
        	
            if (status == BluetoothGatt.GATT_SUCCESS) {
            	Log.v("SERVICES", "services discovered on " + gatt.getDevice().getAddress());
            	
            	// we're pulling a specific service
            	BluetoothGattService s = gatt.getService(UUID.fromString(strSvcUuidBase));

            	boolean bServiceGood = false;
            	
            	// if we've found a service
            	if (s != null) {
            		Log.v(TAG, "found targeted service");
            		bServiceGood = true;
            		
            		// check to make sure every characteristic we want is advertised in this service
                	for (BleCharacteristic b: serviceDef) {
                		if (s.getCharacteristic(b.uuid) == null) {
                			bServiceGood = false;
                			Log.v(TAG, "characteristic " + b.uuid.toString() + " not found");
                			break;
                		}
                	}
                	
                	Log.v(TAG, "service meets serviceDef");
            		           
            	} else {
            		Log.v(TAG, "can't find service " + strSvcUuidBase);
            	}

            	// if this service is good, we can proceed to parlay with our remote party
            	// OR, you can actually go ahead and issue your READ for the id characteristic
        		if (bServiceGood) {
        			Log.v(TAG, "service definition found; stay connected");

        			bleCentralHandler.connectedServiceGood(gatt.getDevice().getAddress());
        			
        		} else {
        			Log.v(TAG, "service definition not found, disconnect");
        			gatt.disconnect();
        		}

		        
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        // Result of a characteristic read operation
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
            	bleCentralHandler.incomingMissive(gatt.getDevice().getAddress(), characteristic.getUuid(), characteristic.getValue());
            } 
        }
    };

	@Override
	public int getMtu(String remoteAddr) {
		
		int finalMtu = 0;
		
		if (mtuMap.get(remoteAddr) != null) {
			finalMtu = mtuMap.get(remoteAddr);
		}
		
		return finalMtu;
	}
    
}
