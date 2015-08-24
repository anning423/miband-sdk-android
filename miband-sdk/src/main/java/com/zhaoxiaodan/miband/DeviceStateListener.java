package com.zhaoxiaodan.miband;

public interface DeviceStateListener {

	void onConnectionStateChange(int status, int newState);
	
	void onServicesDiscovered(int status);
}
