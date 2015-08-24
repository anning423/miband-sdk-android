package com.zhaoxiaodan.miband;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

public class ActionQueueItem {

	public static final int ACTION_READ_RSSI = 1;
	public static final int ACTION_READ_DESCRIPTOR = 2;
	public static final int ACTION_WRITE_DESCRIPTOR = 3;
	public static final int ACTION_READ_CHARACTERISTIC = 4;
	public static final int ACTION_WRITE_CHARACTERISTIC = 5;

	private int seq;
	private int action;
	private Date date;
	private byte[] data;
	private boolean notify;
	private boolean running;
	private UUID descriptor;
	private UUID characteristic;
	private ActionCallback callback;
	
	public ActionQueueItem(int seq) {
		this.date = new Date();
		this.seq = seq;
	}
	
	public int getSeq() {
		return seq;
	}

	public int getAction() {
		return action;
	}
	
	public void setAction(int action) {
		this.action = action;
	}
	
	public UUID getDescriptor() {
		return descriptor;
	}

	public void setDescriptor(UUID descriptor) {
		this.descriptor = descriptor;
	}

	public UUID getCharacteristic() {
		return characteristic;
	}

	public void setCharacteristic(UUID characteristic) {
		this.characteristic = characteristic;
	}

	public byte[] getData() {
		return data;
	}
	
	public void setData(byte[] data) {
		this.data = data;
	}

	public boolean isNotify() {
		return notify;
	}

	public void setNotify(boolean notify) {
		this.notify = notify;
	}

	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

	public ActionCallback getCallback() {
		return callback;
	}
	
	public void setCallback(ActionCallback callback) {
		this.callback = callback;
	}

	@Override
	public String toString() {
		return "ActionQueueItem{" +
				"action=" + action +
				", seq=" + seq +
				", date=" + date +
				", data=" + Arrays.toString(data) +
				", notify=" + notify +
				", running=" + running +
				", descriptor=" + descriptor +
				", characteristic=" + characteristic +
				", callback=" + callback +
				'}';
	}
}
