package com.example.blebenchmark;

import android.os.AsyncTask;
import android.util.Log;

// params, progress, result
public class TestAsync extends AsyncTask <Integer, Void, Void> {

	private static final String TAG = "TASK";

	protected void onPreExecute() {
		Log.v(TAG, "pre-execute");
	}

	@Override
	protected Void doInBackground(Integer... params) {
		// TODO Auto-generated method stub
		return null;
	}
	


}
