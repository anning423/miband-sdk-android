package com.zhaoxiaodan.miband;

import java.util.HashMap;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.zhaoxiaodan.miband.model.Profile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

class BluetoothIO extends BluetoothGattCallback
{
	private static final String		TAG				= "BluetoothIO";
	Handler							handler			= new Handler(Looper.getMainLooper());
	AtomicInteger					seq				= new AtomicInteger();
	BluetoothGatt					gatt;
	BluetoothDevice					device;
	DeviceStateListener				deviceListener;

	Queue<ActionQueueItem> actionQueue = new ConcurrentLinkedQueue<ActionQueueItem>();
	HashMap<UUID, NotifyListener>	notifyListeners	= new HashMap<UUID, NotifyListener>();
	
	public synchronized void connect(Context context, BluetoothDevice device, DeviceStateListener callback)
	{
		LogUtil.d(TAG, "connect to device %s", device);
		this.deviceListener = callback;
		this.device = device;
		device.connectGatt(context, true, BluetoothIO.this);
	}
	
	public synchronized void connect(final Context context, final DeviceStateListener callback)
	{
		LogUtil.d(TAG, "scan and connect to first device");
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		adapter.startLeScan(new LeScanCallback() {
			@Override
			public void onLeScan(final BluetoothDevice device, final int rssi,
								 final byte[] scanRecord) {
				LogUtil.d(TAG,
						"onLeScan: name:" + device.getName() + ",uuid:"
								+ device.getUuids() + ",add:"
								+ device.getAddress() + ",type:"
								+ device.getType() + ",bondState:"
								+ device.getBondState() + ",rssi:" + rssi);
				BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
				adapter.stopLeScan(this);
				connect(context, device, callback);
			}
		});
	}
	
	public synchronized void disconnect()
	{
		if (this.gatt != null) {
			this.gatt.disconnect();
			this.gatt = null;
			LogUtil.d(TAG, "device is disconnected");
		}
		this.device = null;
		this.deviceListener = null;
	}
	
	public synchronized BluetoothDevice getDevice()
	{
		return device;
	}
	
	public synchronized void writeAndRead(final UUID uuid, byte[] valueToWrite, final ActionCallback callback)
	{
		LogUtil.d(TAG, "writeAndRead: uuid=%s", uuid);
		ActionCallback readCallback = new ActionCallback() {
			
			@Override
			public void onSuccess(Object characteristic)
			{
				BluetoothIO.this.readCharacteristic(uuid, callback);
			}
			
			@Override
			public void onFail(int errorCode, String msg)
			{
				callback.onFail(errorCode, msg);
			}
		};
		this.writeCharacteristic(uuid, valueToWrite, readCallback);
	}
	
	public synchronized void writeCharacteristic(UUID uuid, byte[] value, ActionCallback callback)
	{
		ActionQueueItem item = new ActionQueueItem(seq.getAndIncrement());
		item.setAction(ActionQueueItem.ACTION_WRITE_CHARACTERISTIC);
		item.setCharacteristic(uuid);
		item.setData(value);
		item.setCallback(callback);
		LogUtil.d(TAG, "writeCharacteristic: item=%s", item);
		actionQueue.add(item);
		dispatchActionQueue();
	}
	
	public synchronized void readCharacteristic(UUID uuid, ActionCallback callback)
	{
		ActionQueueItem item = new ActionQueueItem(seq.getAndIncrement());
		item.setAction(ActionQueueItem.ACTION_READ_CHARACTERISTIC);
		item.setCharacteristic(uuid);
		item.setCallback(callback);
		LogUtil.d(TAG, "readCharacteristic item=%s", item);
		actionQueue.add(item);
		dispatchActionQueue();
	}
	
	public synchronized void readRssi(ActionCallback callback)
	{
		ActionQueueItem item = new ActionQueueItem(seq.getAndIncrement());
		item.setAction(ActionQueueItem.ACTION_READ_RSSI);
		item.setCallback(callback);
		LogUtil.d(TAG, "readRssi: item=%s", item);
		actionQueue.add(item);
		dispatchActionQueue();
	}
	
	public synchronized void setNotifyListener(UUID characteristicId, NotifyListener listener)
	{
		if(this.notifyListeners.containsKey(characteristicId))
		{
			return;
		}

		this.notifyListeners.put(characteristicId, listener);

		ActionQueueItem item = new ActionQueueItem(seq.getAndIncrement());
		item.setAction(ActionQueueItem.ACTION_WRITE_DESCRIPTOR);
		item.setCharacteristic(characteristicId);
		item.setNotify(true);
		item.setDescriptor(Profile.UUID_DESCRIPTOR_UPDATE_NOTIFICATION);
		item.setData(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		LogUtil.d(TAG, "setNotifyListener: item=%s", item);
		actionQueue.add(item);
		dispatchActionQueue();
	}
	
	@Override
	public synchronized void onConnectionStateChange(BluetoothGatt gatt, final int status, final int newState)
	{
		super.onConnectionStateChange(gatt, status, newState);
		
		LogUtil.d(TAG, "onConnectionStateChange: status=%d, newState=%d", status, newState);
		
		handler.post(new Runnable() {
			@Override
			public void run() {
				DeviceStateListener listener = deviceListener;
				if (listener != null) {
					listener.onConnectionStateChange(status, newState);
				}
			}
		});
		
		if (newState == BluetoothProfile.STATE_CONNECTED)
		{
			LogUtil.d(TAG, "onConnectionStateChange: to discover services");
			cancelActionQueue();
			this.gatt = gatt;
			this.gatt.discoverServices();
		}
		else if (newState == BluetoothProfile.STATE_DISCONNECTED)
		{
			LogUtil.d(TAG, "onConnectionStateChange: clear this.gatt");
			this.gatt = null;
		}
	}

	@Override
	public synchronized void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		super.onDescriptorWrite(gatt, descriptor, status);

		ActionQueueItem item = actionQueue.peek();
		LogUtil.d(TAG, "onDescriptorWrite: item=%s, uuid=%s, status=%d", item, descriptor.getUuid(), status);
		if (!validateRunningItem(item, ActionQueueItem.ACTION_WRITE_DESCRIPTOR))
		{
			return;
		}

		if (BluetoothGatt.GATT_SUCCESS == status)
		{
			this.onSuccess(item, descriptor);
		} else
		{
			this.onFail(item, status, "onDescriptorWrite: fail");
		}
	}

	@Override
	public synchronized void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
	{
		super.onCharacteristicRead(gatt, characteristic, status);

		ActionQueueItem item = actionQueue.peek();
		LogUtil.d(TAG, "onCharacteristicRead: item=%s, uuid=%s, status=%d", item, characteristic.getUuid(), status);
		if (!validateRunningItem(item, ActionQueueItem.ACTION_READ_CHARACTERISTIC))
		{
			return;
		}
		
		if (BluetoothGatt.GATT_SUCCESS == status)
		{
			this.onSuccess(item, characteristic);
		} else
		{
			this.onFail(item, status, "onCharacteristicRead: fail");
		}
	}
	
	@Override
	public synchronized void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
	{
		super.onCharacteristicWrite(gatt, characteristic, status);
		
		ActionQueueItem item = actionQueue.peek();
		LogUtil.d(TAG, "onCharacteristicWrite: item=%s, uuid=%s, status=%d", item, characteristic.getUuid(), status);
		if (!validateRunningItem(item, ActionQueueItem.ACTION_WRITE_CHARACTERISTIC))
		{
			return;
		}
		
		if (BluetoothGatt.GATT_SUCCESS == status)
		{
			this.onSuccess(item, characteristic);
		} else
		{
			this.onFail(item, status, "onCharacteristicWrite: fail");
		}
	}
	
	@Override
	public synchronized void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)
	{
		super.onReadRemoteRssi(gatt, rssi, status);
		
		ActionQueueItem item = actionQueue.peek();
		LogUtil.d(TAG, "onReadRemoteRssi: item=%s, rssi=%d, status=%d", item, rssi, status);
		if (!validateRunningItem(item, ActionQueueItem.ACTION_READ_RSSI))
		{
			return;
		}
		
		if (BluetoothGatt.GATT_SUCCESS == status)
		{
			this.onSuccess(item, rssi);
		} else
		{
			this.onFail(item, status, "onReadRemoteRssi: fail");
		}
	}
	
	@Override
	public synchronized void onServicesDiscovered(BluetoothGatt gatt, final int status)
	{
		super.onServicesDiscovered(gatt, status);

		LogUtil.d(TAG, "onServicesDiscovered: status=%d", status);
		
		handler.post(new Runnable() {
			@Override
			public void run() {
				DeviceStateListener listener = deviceListener;
				if (listener != null) {
					listener.onServicesDiscovered(status);
				}
			}
		});
	}
	
	@Override
	public synchronized void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic)
	{
		super.onCharacteristicChanged(gatt, characteristic);

		handler.post(new Runnable() {
			@Override
			public void run() {
				NotifyListener listener = notifyListeners.get(characteristic.getUuid());
				if (listener != null) {
					listener.onNotify(characteristic.getValue());
				}
			}
		});
	}
	
	private synchronized void onSuccess(final ActionQueueItem item, final Object data) {
		LogUtil.d(TAG, "onSuccess: item=%s, data%s", item, data);
		handler.post(new Runnable()
		{
			@Override
			public void run()
			{
				ActionCallback callback = item.getCallback();
				if (callback != null)
				{
					callback.onSuccess(data);
				}
			}
		});
		actionQueue.remove(item);
		dispatchActionQueue();
	}
	
	private synchronized void onFail(final ActionQueueItem item, final int errorCode, final String msg)
	{
		LogUtil.e(TAG, "onFail: item=%s, err=%d, msg=%s", item, errorCode, msg);
		handler.post(new Runnable()
		{
			@Override
			public void run()
			{
				ActionCallback callback = item.getCallback();
				if (callback != null)
				{
					callback.onFail(errorCode, msg);
				}
			}
		});
		actionQueue.remove(item);
		dispatchActionQueue();
	}

	private synchronized void cancelActionQueue()
	{
		LogUtil.d(TAG, "cancelActionQueue: size=%d", actionQueue.size());
		ActionQueueItem item = null;
		while ((item = actionQueue.peek()) != null) {
			onFail(item, -1, "cancelled, item=" + item);
		}
	}

	private synchronized void dispatchActionQueue()
	{
		LogUtil.d(TAG, "dispatchActionQueue: size=%d", actionQueue.size());
		ActionQueueItem item = actionQueue.peek();
		if (item == null) {
			return;
		}
		if (item.isRunning()) {
			LogUtil.d(TAG, "dispatchActionQueue: item is running. item=%s", item);
			return;
		}
		
		item.setRunning(true);
		
		switch (item.getAction()) {
		case ActionQueueItem.ACTION_READ_RSSI:
			dispatchReadRssi(item);
			break;
		case ActionQueueItem.ACTION_WRITE_DESCRIPTOR:
			dispatchWriteDescriptor(item);
			break;
		case ActionQueueItem.ACTION_READ_CHARACTERISTIC:
			dispatchReadCharacteristic(item);
			break;
		case ActionQueueItem.ACTION_WRITE_CHARACTERISTIC:
			dispatchWriteCharacteristic(item);
			break;
		case ActionQueueItem.ACTION_READ_DESCRIPTOR:
		default:
			LogUtil.d(TAG, "dispatchActionQueue: action is not supported. item=%s", item);
			actionQueue.remove(item);
			dispatchActionQueue();
			break;
		}
	}
	
	private synchronized void dispatchReadRssi(ActionQueueItem item)
	{
		try
		{
			if (null == this.gatt)
			{
				this.onFail(item, -1, "dispatchReadRssi: this.gatt is null");
				return;
			}
			
			if (false == this.gatt.readRemoteRssi())
			{
				this.onFail(item, -1, "dispatchReadRssi: this.gatt.readRemoteRssi() return false");
			}
		} catch (Throwable tr)
		{
			this.onFail(item, -1, "dispatchReadRssi: " + tr.getMessage());
		}
	}

	private synchronized void dispatchWriteDescriptor(ActionQueueItem item)
	{
		try
		{
			if (null == this.gatt)
			{
				this.onFail(item, -1, "dispatchWriteDescriptor: this.gatt is null");
				return;
			}

			BluetoothGattService service = this.gatt.getService(Profile.UUID_SERVICE_MILI);
			if (null == service)
			{
				this.onFail(item, -1, "dispatchWriteDescriptor: service is null");
				return;
			}

			BluetoothGattCharacteristic chara = service.getCharacteristic(item.getCharacteristic());
			if (null == chara)
			{
				this.onFail(item, -1, "dispatchWriteDescriptor: chara is null");
				return;
			}
			this.gatt.setCharacteristicNotification(chara, item.isNotify());

			BluetoothGattDescriptor desc = chara.getDescriptor(item.getDescriptor());
			if (null == desc)
			{
				this.onFail(item, -1, "dispatchWriteDescriptor: desc is null");
				return;
			}

			desc.setValue(item.getData());
			if (false == this.gatt.writeDescriptor(desc))
			{
				this.onFail(item, -1, "dispatchWriteDescriptor: this.gatt.writeDescriptor() return false");
			}
		} catch (Throwable tr)
		{
			this.onFail(item, -1, "dispatchWriteDescriptor: " + tr.getMessage());
		}
	}

	private synchronized void dispatchReadCharacteristic(ActionQueueItem item)
	{
		try
		{
			if (null == this.gatt)
			{
				this.onFail(item, -1, "dispatchReadCharacteristic: this.gatt is null");
				return;
			}
			
			BluetoothGattService service = this.gatt.getService(Profile.UUID_SERVICE_MILI);
			if (null == service)
			{
				this.onFail(item, -1, "dispatchReadCharacteristic: service is null");
				return;
			}
			
			BluetoothGattCharacteristic chara = service.getCharacteristic(item.getCharacteristic());
			if (null == chara)
			{
				this.onFail(item, -1, "dispatchReadCharacteristic: chara is null");
				return;
			}
			
			if (false == this.gatt.readCharacteristic(chara))
			{
				this.onFail(item, -1, "dispatchReadCharacteristic: this.gatt.readCharacteristic() return false");
			}
		} catch (Throwable tr)
		{
			this.onFail(item, -1, "dispatchReadCharacteristic: " + tr.getMessage());
		}
	}
	
	private synchronized void dispatchWriteCharacteristic(ActionQueueItem item)
	{
		try
		{
			if (null == this.gatt)
			{
				this.onFail(item, -1, "dispatchWriteCharacteristic: this.gatt is null");
				return;
			}
			
			BluetoothGattService service = this.gatt.getService(Profile.UUID_SERVICE_MILI);
			if (null == service)
			{
				this.onFail(item, -1, "dispatchWriteCharacteristic: service is null");
				return;
			}

			BluetoothGattCharacteristic chara = service.getCharacteristic(item.getCharacteristic());
			if (null == chara)
			{
				this.onFail(item, -1, "dispatchWriteCharacteristic: chara is null");
				return;
			}
			
			chara.setValue(item.getData());
			if (false == this.gatt.writeCharacteristic(chara))
			{
				this.onFail(item, -1, "dispatchWriteCharacteristic: this.gatt.writeCharacteristic() return false");
			}
		} catch (Throwable tr)
		{
			this.onFail(item, -1, "dispatchWriteCharacteristic: " + tr.getMessage());
		}
	}
	
	private synchronized boolean validateRunningItem(ActionQueueItem item, int action)
	{
		if (item == null)
		{
			LogUtil.e(TAG, "validateRunningItem: item is null");
			return false;
		}
		if (!item.isRunning())
		{
			LogUtil.e(TAG, "validateRunningItem: item not running. item=%s", item);
			dispatchActionQueue();
			return false;
		}
		if (item.getAction() != action)
		{
			LogUtil.e(TAG, "validateRunningItem: action %d not match. item=%s", item);
			actionQueue.remove(item);
			dispatchActionQueue();
			return false;
		}
		return true;
	}
}
