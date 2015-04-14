package com.example.blebenchmark;

public class BenchMessage {

	BenchMessage() {
		ExpectedPackets = 0;
		IncompleteReceives = 0;
	}
	
	
	public byte[] MessageBytes;
	
	public int ParentMessage;
	
	public int ExpectedPackets;
	
	public long MillisecondStart;
	
	public long MillisecondStop;
	
	public int IncompleteReceives;
	
	public int IncompleteSends;
	
}
