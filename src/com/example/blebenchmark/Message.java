package com.example.blebenchmark;

import android.content.ContentProvider;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/** A custom Content Provider to do the database operations */
public class Message extends ContentProvider {
 
    public static final String PROVIDER_NAME = "example.blebenchmark.message";
 
    /** A uri to do operations on cust_master table. A content provider is identified by its uri */
    public static final Uri CONTENT_URI = Uri.parse("content://" + PROVIDER_NAME + "/messages" );
 
    /** Constants to identify the requested operation */
    private static final int MESSAGES = 1;
 
    private static final UriMatcher uriMatcher ;

	private static final String TAG = "Message";
    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(PROVIDER_NAME, "messages", MESSAGES);
    }
 
    /** This content provider does the database operations by this object */
    FriendsDb mFriendsDB;
 
    /** A callback method which is invoked when the content provider is starting up */
    @Override
    public boolean onCreate() {
    	mFriendsDB = new FriendsDb(getContext());
        return true;
    }
 
    @Override
    public String getType(Uri uri) {
        return null;
    }
 
    /** A callback method which is by the default content uri */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    	
    	Log.v(TAG, "public Cursor query() called");
        if(uriMatcher.match(uri)==MESSAGES){
        	
        	Cursor c = mFriendsDB.fetchMsgsAbbrev(); 
        	
            return c;
        }else{
        	Log.v(TAG, "nothing called");
            return null;
        }
    }
 
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }
 
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO Auto-generated method stub
        return null;
    }
 
    @Override
    public int update(Uri uri, ContentValues values, String selection,
        String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }
}