package com.zhaoxiaodan.miband;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.util.Log;

public class LogUtil {

	private static final Object LOCKER = new Object();
	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

	private static PrintWriter mLogWriter = null;

	public static void logToDir(File logRoot) throws FileNotFoundException {
		synchronized(LOCKER) {
			logRoot.mkdirs();
			String fileName = genFileName();
			File logFile = new File(logRoot, fileName);
			mLogWriter = new PrintWriter(new FileOutputStream(logFile), true);
		}
	}

	public static PrintWriter getWriter() {
		return mLogWriter;
	}

	public static void d(String tag, String format, Object... args) {
		if (args != null && args.length > 0) {
			format = String.format(format, args);
		}
		d(tag, format, (Throwable)null);
	}
	
	public static void d(String tag, String msg, Throwable tr) {
		Log.d(tag, msg, tr);
		writeLog("D", tag, msg, tr);
	}
	
	public static void e(String tag, String format, Object... args) {
		if (args != null && args.length > 0) {
			format = String.format(format, args);
		}
		e(tag, format, (Throwable)null);
	}
	
	public static void e(String tag, String msg, Throwable tr) {
		Log.e(tag, msg, tr);
		writeLog("E", tag, msg, tr);
	}
	
	private static void writeLog(String level, String tag, String msg, Throwable tr) {
		synchronized(LOCKER) {
			if (mLogWriter != null) {
				String time = DATE_FORMAT.format(new Date());
				mLogWriter.printf("%s %s %s %s", time, level, tag, msg);
				mLogWriter.println();
				if (tr != null) {
					mLogWriter.printf("%s %s %s %s", time, level, tag, tr);
					mLogWriter.println();
				}
			}
		}
	}
	
	private static String genFileName() {
		DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS");
		return String.format("Log_%s.txt", dateFormat.format(new Date()));
	}
}
