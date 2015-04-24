package simpble;

import java.util.List;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Build;

// http://stackoverflow.com/questions/12272397/android-backward-compatibility-but-still-utilise-latest-api-features
public abstract class BleCentralShared {

	private static BleCentralShared bleCentralShared;
	
	public static BleCentralShared getCentralShared(BluetoothAdapter btA, Context ctx, BleCentralHandler myHandler, String serviceUuidBase, long defaultScanInMs, int ScanMode) {
		if (bleCentralShared == null) {
			
			if (Build.VERSION.SDK_INT < 20) {
				bleCentralShared = new BleCentralCompat(btA, ctx, myHandler, serviceUuidBase, defaultScanInMs, ScanMode);
			} else {
				bleCentralShared = new BleCentral(btA, ctx, myHandler, serviceUuidBase, defaultScanInMs, ScanMode);
			}
			
		}
		
		return bleCentralShared;
	}
	
	public abstract void setRequiredServiceDef(List<BleCharacteristic> bleChars);
	public abstract void disconnectAddress(String btAddress);
	public abstract boolean submitCharacteristicWriteRequest(String remoteAddr, UUID uuidChar, final byte[] val);
	public abstract boolean submitCharacteristicReadRequest(String remoteAddr, UUID uuidChar);
	public abstract void setScanDuration(int duration);
	public abstract void scanForPeripherals(final boolean enable);
	public abstract void connectAddress(String btAddress);
	public abstract boolean submitSubscription(String remoteAddr, UUID uuidChar);
	public abstract void requestMtu(String remoteAddr);
	public abstract int getMtu(String remoteAddr);
	
}
