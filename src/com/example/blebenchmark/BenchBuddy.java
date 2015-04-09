package com.example.blebenchmark;

import android.util.SparseArray;

public class BenchBuddy {
	
	public String SenderAddress;

	public SparseArray<BenchMessage> benchMessages;
	
	BenchBuddy() {
		benchMessages = new SparseArray<BenchMessage>();
	}

}
