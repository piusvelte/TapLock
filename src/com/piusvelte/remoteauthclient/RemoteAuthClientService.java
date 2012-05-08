package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

public class RemoteAuthClientService extends Service {
	private static final String TAG = "RemoteAuthClientService";
	protected static final String ACTION_NFC_READ = "com.piusvelte.remoteauthclient.NFC_READ";
	protected static final String EXTRA_TAGGED_DEVICE = "com.piusvelte.remoteauthclient.TAGGED_DEVICE";
	private BluetoothAdapter mBtAdapter;
	private AcceptThread mSecureAcceptThread;
	private AcceptThread mInsecureAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private byte[] mPendingMessage;
	private String mPendingAddress;
	private String mPendingUuid;
	private boolean mPendingSecure = false;
	private static final int REQUEST_NONE = 0;
	private static final int REQUEST_WRITE = 1;
	private static final int REQUEST_DISCOVERY = 2;
	private static final int REQUEST_SERVICE = 3;
	private int REQUEST = REQUEST_NONE;
	private boolean mStartedBT = false;
	// Unique UUID for this application
	private static final String sSPD = "RemoteAuth";
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null) {
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
			}
		}

		@Override
		public void write(String address, String message, boolean secure) throws RemoteException {
			writeUsingUuid(address, message, secure, sRemoteAuthServerUUID.toString());
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			REQUEST = REQUEST_DISCOVERY;
			if (mBtAdapter.isEnabled()) {
				// If we're already discovering, stop it
				if (mBtAdapter.isDiscovering()) {
					mBtAdapter.cancelDiscovery();
				}
				// Request discover from BluetoothAdapter
				mBtAdapter.startDiscovery();
			} else {
				mBtAdapter.enable();
			}
		}

		@Override
		public void stop() throws RemoteException {
			if (mStartedBT) {
				mBtAdapter.disable();
			}
		}

		@Override
		public void writeUsingUuid(String address, String message, boolean secure, String uuid) throws RemoteException {
			byte[] out = message.getBytes();
			REQUEST = REQUEST_WRITE;
			if (mBtAdapter.isEnabled()) {
				if ((mConnectedThread != null) && mConnectedThread.isConnected(address)) {
					// Create temporary object
					ConnectedThread r;
					// Synchronize a copy of the ConnectedThread
					synchronized (this) {
						r = mConnectedThread;
					}
					// Perform the write unsynchronized
					r.write(out);
				} else {
					mPendingMessage = out;
					connectDevice(address, secure, uuid);
				}
			} else {
				mPendingMessage = out;
				mPendingAddress = address;
				mPendingSecure = secure;
				mPendingUuid = uuid;
				mStartedBT = true;
				mBtAdapter.enable();
			}
		}

		@Override
		public void requestServiceDiscovery(String address) throws RemoteException {
			REQUEST = REQUEST_SERVICE;
			if (mBtAdapter.isEnabled()) {
				BluetoothDevice device = getDevice(address);
				device.fetchUuidsWithSdp();
			} else {
				mPendingAddress = address;
				mBtAdapter.enable();
			}
		}
	};

	// The BroadcastReceiver that listens for discovered devices and
	// changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("...btadapter enabled...");
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					stopConnectionThreads();
					switch (REQUEST) {
					case REQUEST_NONE:
						break;
					case REQUEST_WRITE:
						if ((mPendingMessage != null) && (mPendingAddress != null) && (mPendingUuid != null)) {
							connectDevice(mPendingAddress, mPendingSecure, mPendingUuid);
						}
						break;
					case REQUEST_DISCOVERY:
						mBtAdapter.startDiscovery();
						break;
					case REQUEST_SERVICE:
						BluetoothDevice device = getDevice(mPendingAddress);
						device.fetchUuidsWithSdp();
						break;
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopConnectionThreads();
					setListen(false);
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// If it's already paired, skip it, because it's been listed already
				if (REQUEST == REQUEST_DISCOVERY) {
					if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
						if (mUIInterface != null) {
							try {
								mUIInterface.setUnpairedDevice(device.getName() + " " + device.getAddress());
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (REQUEST == REQUEST_DISCOVERY) {
					REQUEST = REQUEST_NONE;
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			} else if (BluetoothDevice.ACTION_UUID.equals(action)) {
				if (REQUEST == REQUEST_SERVICE) {
					REQUEST = REQUEST_NONE;
					if (mUIInterface != null) {
						Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
						try {
							for (Parcelable uuid : uuids) {
								String u = ((ParcelUuid) uuid).toString();
								Log.d(TAG, "service: " + u);
								if (u.startsWith("00001101")) {
									mUIInterface.setService(u);
								}
							}
							mUIInterface.setServiceDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			}
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		filter.addAction(BluetoothDevice.ACTION_UUID);
		registerReceiver(mReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return mServiceInterface;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterReceiver(mReceiver);
		stopConnectionThreads();
		setListen(false);
	}

	private synchronized void setListen(boolean listen) {
		if (listen) {
			if (mSecureAcceptThread != null) {
				mSecureAcceptThread.cancel();
				mSecureAcceptThread = null;
			}
			mSecureAcceptThread = new AcceptThread(true);
			mSecureAcceptThread.start();
			mInsecureAcceptThread = new AcceptThread(false);
			mInsecureAcceptThread.start();
		} else {
			if (mSecureAcceptThread != null) {
				mSecureAcceptThread.cancel();
				mSecureAcceptThread = null;
			}
			if (mInsecureAcceptThread != null) {
				mInsecureAcceptThread.cancel();
				mInsecureAcceptThread = null;
			}
		}
	}

	private void stopConnectionThreads() {
		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
	}

	public BluetoothDevice getDevice(String address) {
		BluetoothDevice device = null;
		Set<BluetoothDevice> devices = mBtAdapter.getBondedDevices();
		Iterator<BluetoothDevice> iter = devices.iterator();
		while (iter.hasNext() && (device == null)) {
			BluetoothDevice d = iter.next();
			if (d.getAddress().equals(address)) {
				device = d;
			}
		}
		if (device == null) {
			device = mBtAdapter.getRemoteDevice(address);
		}
		return device;
	}

	public synchronized void connectDevice(String address, boolean secure, String uuid) {
		// don't reconnect if already connected
		if ((mConnectedThread == null) || (!mConnectedThread.isConnected(address))) {
			Log.d(TAG, "connectDevice " + address);
			BluetoothDevice device = getDevice(address);
			mConnectThread = new ConnectThread(device, secure, uuid);
			if (mConnectThread.hasSocket()) {
				Log.d(TAG, "has socket");
				mConnectThread.start();
			} else {
				mConnectThread.cancel();
				mConnectThread = null;
				setListen(true);
			}
		}
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * @param socket  The BluetoothSocket on which the connection was made
	 * @param device  The BluetoothDevice that has been connected
	 */
	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
		stopConnectionThreads();
		setListen(false);
		
		mConnectedThread = new ConnectedThread(socket);
		if (mConnectedThread.hasStreams()) {
			Log.d(TAG, "has streams");
			mConnectedThread.start();
		} else {
			mConnectedThread = null;
			setListen(true);
		}
	}

	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread(boolean secure) {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				if (secure) {
					tmp = mBtAdapter.listenUsingRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
				} else {
					tmp = mBtAdapter.listenUsingInsecureRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (mConnectedThread == null) {
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
					break;
				}

				// If a connection was accepted
				if (socket != null) {
					synchronized (RemoteAuthClientService.this) {
						if (mConnectedThread != null) {
							// Either not ready or already connected. Terminate new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, e.toString());
							}
							break;
						} else {
							connected(socket, socket.getRemoteDevice());
						}
					}
				}
			}
		}

		public void cancel() {
			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
		}
	}

	/**
	 * This thread runs while attempting to make an outgoing connection
	 * with a device. It runs straight through; the connection either
	 * succeeds or fails.
	 */
	private class ConnectThread extends Thread {
		private BluetoothSocket mSocket;
		private BluetoothDevice mDevice;

		public ConnectThread(BluetoothDevice device, boolean secure, String uuid) {
			mDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				Log.d(TAG, "create socket");
				if (secure) {
					tmp = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid));
				} else {
					tmp = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid));
				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mSocket = tmp;
		}

		public boolean hasSocket() {
			return (mSocket != null);
		}

		public void run() {
			Log.d(TAG, "socket connect");
			mBtAdapter.cancelDiscovery();

			try {
				mSocket.connect();
				Log.d(TAG, "connected");
			} catch (IOException e) {
				Log.e(TAG, "connection failed: " + e.toString());
				try {
					mSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, e2.toString());
				} finally {
					mSocket = null;
				}
				// fallback to listening
				setListen(true);
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (RemoteAuthClientService.this) {
				mConnectThread = null;
			}

			if (hasSocket() && mSocket.isConnected()) {
				// Start the connected thread
				connected(mSocket, mDevice);
			}
		}

		public void cancel() {
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device.
	 * It handles all incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private BluetoothSocket mSocket;
		private InputStream mInStream;
		private OutputStream mOutStream;

		public ConnectedThread(BluetoothSocket socket) {
			if ((socket == null) || (!socket.isConnected())) {
				Log.d(TAG, "connected failure, bad socket");
				return;
			}
			mSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the BluetoothSocket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mInStream = tmpIn;
			mOutStream = tmpOut;
		}

		public boolean hasStreams() {
			return (mInStream != null) && (mOutStream != null);
		}

		public void run() {
			if ((REQUEST == REQUEST_WRITE) && (mPendingMessage != null)) {
				write(mPendingMessage);
				mPendingMessage = null;
				REQUEST = REQUEST_NONE;
			}

			// Keep listening to the InputStream while connected
			while (true) {
				try {
					byte[] buffer = new byte[1024];
					int readBytes = mInStream.read(buffer);
					// construct a string from the valid bytes in the buffer
					String message = new String(buffer, 0, readBytes);
					Log.d(TAG, "message: " + message);
				} catch (IOException e) {
					Log.e(TAG, "disconnected", e);
					break;
				}
			}
		}

		public boolean isConnected(String address) {
			BluetoothDevice device = mSocket.getRemoteDevice();
			if (device == null) {
				return false;
			} else {
				return device.getAddress().equals(address);
			}
		}

		/**
		 * Write to the connected OutStream.
		 * @param buffer  The bytes to write
		 */
		public void write(byte[] buffer) {
			Log.d(TAG, "writing...");
			try {
				mOutStream.write(buffer);
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, "close() of connect socket failed", e);
				}
			}
		}
	}

}
