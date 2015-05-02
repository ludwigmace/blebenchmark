package com.example.blebenchmark;

import android.util.SparseArray;

public class BenchBuddy {
	
	public String SenderAddress;

	public SparseArray<BenchMessage> benchMessages;
	public boolean IdentitySent;
	public String Fingerprint; 
	
	BenchBuddy() {
		IdentitySent = false;
		benchMessages = new SparseArray<BenchMessage>();
	}

}
