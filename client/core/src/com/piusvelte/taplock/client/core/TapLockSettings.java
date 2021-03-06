/*
 * TapLock
 * Copyright (C) 2012 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */
package com.piusvelte.taplock.client.core;

import static com.piusvelte.taplock.client.core.TapLock.PRO;
import static com.piusvelte.taplock.client.core.TapLock.GOOGLE_AD_ID;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_LOCK;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_REMOVE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_TAG;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_TOGGLE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_UNLOCK;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_DOWNLOAD_SDCARD;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_DOWNLOAD_EMAIL;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_COPY_DEVICE_URI;
import static com.piusvelte.taplock.client.core.TapLock.KEY_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLock.KEY_NAME;
import static com.piusvelte.taplock.client.core.TapLock.KEY_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.DEFAULT_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_INFO;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_NAME;
import static com.piusvelte.taplock.client.core.TapLock.KEY_DEVICES;
import static com.piusvelte.taplock.client.core.TapLock.KEY_PREFS;
import static com.piusvelte.taplock.client.core.TapLock.KEY_SERVER_VERSION;
import static com.piusvelte.taplock.client.core.TapLock.SERVER_VERSION;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.ads.*;

import android.app.AlertDialog;

import android.app.ListActivity;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.AssetManager;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Toast;

public class TapLockSettings extends ListActivity implements ServiceConnection, OnSharedPreferenceChangeListener {
	private static final String TAG = "TapLockSettings";
	private ProgressDialog mProgressDialog;
	private AlertDialog mDialog;
	private ArrayList<JSONObject> mDevices = new ArrayList<JSONObject>();
	private ArrayList<JSONObject> mUnpairedDevices = new ArrayList<JSONObject>();
	private boolean mShowTapLockSettingsInfo = true;
	private static final int REMOVE_ID = Menu.FIRST;

	// NFC
	private NfcAdapter mNfcAdapter = null;
	private boolean mInWriteMode = false;

	private ITapLockService mServiceInterface;
	private ITapLockUI.Stub mUIInterface = new ITapLockUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
		}

		@Override
		public void setUnpairedDevice(String device) throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				try {
					JSONObject deviceJObj = new JSONObject(device);
					mUnpairedDevices.add(deviceJObj);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public void setDiscoveryFinished() throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				mProgressDialog.cancel();
				if (mUnpairedDevices.size() > 0) {
					final String[] unpairedDevicesStr = new String[mUnpairedDevices.size()];
					for (int i = 0, l = unpairedDevicesStr.length; i < l; i++) {
						JSONObject deviceJObj = mUnpairedDevices.get(i);
						try {
							unpairedDevicesStr[i] = deviceJObj.getString(KEY_NAME) + " " + deviceJObj.getString(KEY_ADDRESS);
						} catch (JSONException e) {
							Log.e(TAG, e.toString());
						}
					}
					mDialog = new AlertDialog.Builder(TapLockSettings.this)
					.setItems(unpairedDevicesStr, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							if (mServiceInterface != null) {
								JSONObject deviceJObj = mUnpairedDevices.get(which);
								try {
									mServiceInterface.pairDevice(deviceJObj.getString(KEY_ADDRESS));
								} catch (RemoteException e) {
									Log.e(TAG, e.toString());
									Toast.makeText(getApplicationContext(), "service unavailable", Toast.LENGTH_SHORT).show();
								} catch (JSONException e) {
									Log.e(TAG, e.toString());
								}
							} else
								Toast.makeText(getApplicationContext(), "service unavailable", Toast.LENGTH_SHORT).show();
						}
					})
					.create();
					mDialog.show();
				} else
					Toast.makeText(getApplicationContext(), getString(R.string.msg_nodevices), Toast.LENGTH_SHORT).show();
			}
		}

		@Override
		public void setStateFinished(boolean pass) throws RemoteException {
			Intent intent = getIntent();
			if (intent != null) {
				if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) && intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES))
					TapLockSettings.this.finish();
			}
		}

		@Override
		public void setPairingResult(String name, String address) throws RemoteException {
			addNewDevice(name, address);
		}

		@Override
		public void setPassphrase(String address, String passphrase) throws RemoteException {
			if (address == null)
				Toast.makeText(getApplicationContext(), "failed to set passphrase on TapLockServer", Toast.LENGTH_SHORT).show();
			storeDevices();
		}

		@Override
		public void setBluetoothEnabled() throws RemoteException {
			addDevice();
		}

	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		registerForContextMenu(getListView());
		if (!getPackageName().toLowerCase().contains(PRO)) {
			AdView adView = new AdView(this, AdSize.BANNER, GOOGLE_AD_ID);
			((LinearLayout) findViewById(R.id.ad)).addView(adView);
			adView.loadAd(new AdRequest());
		}
		//NFC
		mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());

	}

	@Override 
	public void onNewIntent(Intent intent) {
		// handle NFC intents
		setIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		Intent intent = getIntent();
		if (mInWriteMode) {
			if (intent != null) {
				String action = intent.getAction();
				if (mInWriteMode && NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) && intent.hasExtra(EXTRA_DEVICE_NAME)) {
					Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
					String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
					if ((tag != null) && (name != null)) {
						// write the device and address
						String lang = "en";
						// don't write the passphrase!
						byte[] textBytes = name.getBytes();
						byte[] langBytes = null;
						int langLength = 0;
						try {
							langBytes = lang.getBytes("US-ASCII");
							langLength = langBytes.length;
						} catch (UnsupportedEncodingException e) {
							Log.e(TAG, e.toString());
						}
						int textLength = textBytes.length;
						byte[] payload = new byte[1 + langLength + textLength];

						// set status byte (see NDEF spec for actual bits)
						payload[0] = (byte) langLength;

						// copy langbytes and textbytes into payload
						if (langBytes != null) {
							System.arraycopy(langBytes, 0, payload, 1, langLength);
						}
						System.arraycopy(textBytes, 0, payload, 1 + langLength, textLength);
						NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, 
								NdefRecord.RTD_TEXT, 
								new byte[0], 
								payload);
						NdefMessage message = new NdefMessage(new NdefRecord[]{record, NdefRecord.createApplicationRecord(getPackageName())});
						// Get an instance of Ndef for the tag.
						Ndef ndef = Ndef.get(tag);
						if (ndef != null) {
							try {
								ndef.connect();
								if (ndef.isWritable()) {
									ndef.writeNdefMessage(message);
								}
								ndef.close();
								Toast.makeText(this, "tag written", Toast.LENGTH_LONG).show();
							} catch (IOException e) {
								Log.e(TAG, e.toString());
							} catch (FormatException e) {
								Log.e(TAG, e.toString());
							}
						} else {
							NdefFormatable format = NdefFormatable.get(tag);
							if (format != null) {
								try {
									format.connect();
									format.format(message);
									format.close();
									Toast.makeText(getApplicationContext(), "tag written", Toast.LENGTH_LONG);
								} catch (IOException e) {
									Log.e(TAG, e.toString());
								} catch (FormatException e) {
									Log.e(TAG, e.toString());
								}
							}
						}
						mNfcAdapter.disableForegroundDispatch(this);
					}
				}
			}
			mInWriteMode = false;
		} else {
			SharedPreferences sp = getSharedPreferences(KEY_PREFS, MODE_PRIVATE);
			onSharedPreferenceChanged(sp, KEY_DEVICES);
			// check if configuring a widget
			if (intent != null) {
				Bundle extras = intent.getExtras();
				if (extras != null) {
					final String[] displayNames = TapLock.getDeviceNames(mDevices);
					final int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
						mDialog = new AlertDialog.Builder(TapLockSettings.this)
						.setTitle("Select device for widget")
						.setItems(displayNames, new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog, int which) {
								// set the successful widget result
								Intent resultValue = new Intent();
								resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
								setResult(RESULT_OK, resultValue);

								// broadcast the new widget to update
								JSONObject deviceJObj = mDevices.get(which);
								dialog.cancel();
								TapLockSettings.this.finish();
								try {
									sendBroadcast(TapLock.getPackageIntent(TapLockSettings.this, TapLockWidget.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).putExtra(EXTRA_DEVICE_NAME, deviceJObj.getString(KEY_NAME)));
								} catch (JSONException e) {
									Log.e(TAG, e.toString());
								}
							}
						})
						.create();
						mDialog.show();
					}
				}
			}
			// start the service before binding so that the service stays around for faster future connections
			startService(TapLock.getPackageIntent(this, TapLockService.class));
			bindService(TapLock.getPackageIntent(this, TapLockService.class), this, BIND_AUTO_CREATE);

			int serverVersion = sp.getInt(KEY_SERVER_VERSION, 0);
			if (mShowTapLockSettingsInfo && (mDevices.size() == 0)) {
				if (serverVersion < SERVER_VERSION)
					sp.edit().putInt(KEY_SERVER_VERSION, SERVER_VERSION).commit();
				mShowTapLockSettingsInfo = false;
				Intent i = TapLock.getPackageIntent(this, TapLockInfo.class);
				i.putExtra(EXTRA_INFO, getString(R.string.info_taplocksettings));
				startActivity(i);
			} else if (serverVersion < SERVER_VERSION) {
				// TapLockServer has been updated
				sp.edit().putInt(KEY_SERVER_VERSION, SERVER_VERSION).commit();
				mDialog = new AlertDialog.Builder(TapLockSettings.this)
				.setTitle(R.string.ttl_hasupdate)
				.setMessage(R.string.msg_hasupdate)
				.setNeutralButton(R.string.button_getserver, new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.cancel();
						
						mDialog = new AlertDialog.Builder(TapLockSettings.this)
						.setTitle(R.string.msg_pickinstaller)
						.setItems(R.array.installer_entries, new DialogInterface.OnClickListener() {
							
							@Override
							public void onClick(DialogInterface dialog, int which) {
								dialog.cancel();
								final String installer_file = getResources().getStringArray(R.array.installer_values)[which];

								mDialog = new AlertDialog.Builder(TapLockSettings.this)
								.setTitle(R.string.msg_pickdownloader)
								.setItems(R.array.download_entries, new DialogInterface.OnClickListener() {

									@Override
									public void onClick(DialogInterface dialog, int which) {
										dialog.cancel();
										String action = getResources().getStringArray(R.array.download_values)[which];
										if (ACTION_DOWNLOAD_SDCARD.equals(action) && copyFileToSDCard(installer_file))
											Toast.makeText(TapLockSettings.this, "Done!", Toast.LENGTH_SHORT).show();
										else if (ACTION_DOWNLOAD_EMAIL.equals(action) && copyFileToSDCard(installer_file)) {
											Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
											emailIntent.setType("application/java-archive");
											emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.email_instructions));
											emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
											emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + Environment.getExternalStorageDirectory().getPath() + "/" + installer_file));
											startActivity(Intent.createChooser(emailIntent, getString(R.string.button_getserver)));
										}
									}

								})
								.create();
								mDialog.show();
							}
						})
						.create();
						mDialog.show();
					}
				})
				.create();
				mDialog.show();
			}
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (!mInWriteMode) {
			if (mServiceInterface != null) {
				try {
					mServiceInterface.stop();
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
				unbindService(this);
			}
			if ((mDialog != null) && mDialog.isShowing())
				mDialog.cancel();
			if ((mProgressDialog != null) && mProgressDialog.isShowing())
				mProgressDialog.cancel();
		}
	}

	@Override
	protected void onListItemClick(ListView list, final View view, int position, final long id) {
		super.onListItemClick(list, view, position, id);
		mDialog = new AlertDialog.Builder(TapLockSettings.this)
		.setItems(R.array.actions_entries, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				String action = getResources().getStringArray(R.array.actions_values)[which];
				int deviceIdx = (int) id;
				JSONObject deviceJObj = mDevices.get(deviceIdx);
				dialog.cancel();
				if (ACTION_UNLOCK.equals(action) || ACTION_LOCK.equals(action) || ACTION_TOGGLE.equals(action)) {
					String name = "uknown";
					try {
						name = deviceJObj.getString(KEY_NAME);
					} catch (JSONException e) {
						e.printStackTrace();
					}
					startActivity(TapLock.getPackageIntent(getApplicationContext(), TapLockToggle.class).setAction(action).putExtra(EXTRA_DEVICE_NAME, name));
				} else if (ACTION_TAG.equals(action)) {
					// write the device to a tag
					mInWriteMode = true;
					try {
						mNfcAdapter.enableForegroundDispatch(TapLockSettings.this,
								PendingIntent.getActivity(TapLockSettings.this, 0, new Intent(TapLockSettings.this, TapLockSettings.this.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_DEVICE_NAME, deviceJObj.getString(KEY_NAME)), 0),
								new IntentFilter[] {new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)},
								null);
					} catch (JSONException e) {
						Log.e(TAG, e.getMessage());
					}
					Toast.makeText(TapLockSettings.this, "Touch tag", Toast.LENGTH_LONG).show();
				} else if (ACTION_REMOVE.equals(action)) {
					mDevices.remove(deviceIdx);
					storeDevices();
				} else if (ACTION_PASSPHRASE.equals(action))
					setPassphrase(deviceIdx);
				else if (ACTION_COPY_DEVICE_URI.equals(action)) {
					ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
					ClipData clip;
					try {
						clip = ClipData.newPlainText(getString(R.string.app_name), String.format(getString(R.string.device_uri), deviceJObj.get(EXTRA_DEVICE_NAME)));
						clipboard.setPrimaryClip(clip);
						Toast.makeText(TapLockSettings.this, "copied to clipboard!", Toast.LENGTH_SHORT).show();
					} catch (JSONException e) {
						Log.e(TAG, e.getMessage());
						Toast.makeText(TapLockSettings.this, getString(R.string.msg_oops), Toast.LENGTH_SHORT).show();
					}
				}
			}
		})
		.create();
		mDialog.show();
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, view, menuInfo);
		menu.add(0, REMOVE_ID, 0, R.string.mn_remove);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case REMOVE_ID:
			// remove device
			int deviceIdx = (int) ((AdapterContextMenuInfo) item.getMenuInfo()).id;
			mDevices.remove(deviceIdx);
			storeDevices();
			return true;
		}
		return super.onContextItemSelected(item);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater menuInflater = getMenuInflater();
		menuInflater.inflate(R.menu.menu_settings, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.button_add_device)
			addDevice();
		else if (itemId == R.id.button_about) {
			mDialog = new AlertDialog.Builder(TapLockSettings.this)
			.setTitle(R.string.button_about)
			.setMessage(R.string.about)
			.setNeutralButton(R.string.button_getserver, new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.cancel();
					
					mDialog = new AlertDialog.Builder(TapLockSettings.this)
					.setTitle(R.string.msg_pickinstaller)
					.setItems(R.array.installer_entries, new DialogInterface.OnClickListener() {
						
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
							final String installer_file = getResources().getStringArray(R.array.installer_values)[which];

							mDialog = new AlertDialog.Builder(TapLockSettings.this)
							.setTitle(R.string.msg_pickdownloader)
							.setItems(R.array.download_entries, new DialogInterface.OnClickListener() {

								@Override
								public void onClick(DialogInterface dialog, int which) {
									dialog.cancel();
									String action = getResources().getStringArray(R.array.download_values)[which];
									if (ACTION_DOWNLOAD_SDCARD.equals(action) && copyFileToSDCard(installer_file))
										Toast.makeText(TapLockSettings.this, "Done!", Toast.LENGTH_SHORT).show();
									else if (ACTION_DOWNLOAD_EMAIL.equals(action) && copyFileToSDCard(installer_file)) {
										Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
										emailIntent.setType("application/java-archive");
										emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.email_instructions));
										emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
										emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + Environment.getExternalStorageDirectory().getPath() + "/" + installer_file));
										startActivity(Intent.createChooser(emailIntent, getString(R.string.button_getserver)));
									}
								}

							})
							.create();
							mDialog.show();
						}
					})
					.create();
					mDialog.show();
				}
			})
			.setPositiveButton(R.string.button_license, new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {
					dialog.cancel();
					mDialog = new AlertDialog.Builder(TapLockSettings.this)
					.setTitle(R.string.button_license)
					.setMessage(R.string.license)
					.create();
					mDialog.show();
				}

			})
			.create();
			mDialog.show();
		}
		return super.onOptionsItemSelected(item);
	}

	private boolean copyFileToSDCard(String filename) {
		AssetManager assetManager = getAssets();
		String state = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))
			Toast.makeText(TapLockSettings.this, R.string.msg_sdcardunavailable, Toast.LENGTH_SHORT).show();
		else {
			try {
				InputStream in = assetManager.open(filename);
				OutputStream out = new FileOutputStream(Environment.getExternalStorageDirectory().getPath() + "/" + filename);
				byte[] buffer = new byte[1024];
				int read;
				while ((read = in.read(buffer)) != -1)
					out.write(buffer, 0, read);
				in.close();
				in = null;
				out.flush();
				out.close();
				out = null;
				return true;
			} catch (IOException e) {
				Log.e(TAG, e.getMessage());
				Toast.makeText(TapLockSettings.this, R.string.msg_oops, Toast.LENGTH_SHORT).show();
			}
		}
		return false;
	}

	private void addDevice() {
		// Get a set of currently paired devices
		BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
		if (btAdapter.isEnabled()) {
			Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
			// launch dialog to select device
			if (pairedDevices.size() > 0) {
				int p = 0;
				final String[] pairedDeviceNames = new String[pairedDevices.size()];
				final String[] pairedDeviceAddresses = new String[pairedDevices.size()];
				for (BluetoothDevice device : pairedDevices) {
					pairedDeviceNames[p] = device.getName();
					pairedDeviceAddresses[p++] = device.getAddress();
				}
				mDialog = new AlertDialog.Builder(TapLockSettings.this)
				.setItems(pairedDeviceNames, new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						addNewDevice(pairedDeviceNames[which], pairedDeviceAddresses[which]);
					}

				})
				.setPositiveButton(getString(R.string.btn_bt_scan), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (mServiceInterface != null) {
							try {
								mServiceInterface.requestDiscovery();
								mProgressDialog = new ProgressDialog(TapLockSettings.this);
								mProgressDialog.setMessage(getString(R.string.msg_scanning));
								mProgressDialog.setCancelable(true);
								mProgressDialog.show();
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
				})
				.create();
				mDialog.show();
			} else {
				if (mServiceInterface != null) {
					try {
						mServiceInterface.requestDiscovery();
						mProgressDialog = new ProgressDialog(this);
						mProgressDialog.setMessage(getString(R.string.msg_scanning));
						mProgressDialog.setCancelable(true);
						mProgressDialog.show();
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
		} else {
			mDialog = new AlertDialog.Builder(TapLockSettings.this)
			.setTitle(R.string.ttl_enablebt)
			.setMessage(R.string.msg_enablebt)
			.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					if (mServiceInterface != null) {
						try {
							mServiceInterface.enableBluetooth();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			})
			.create();
			mDialog.show();
		}
	}

	protected void setPassphrase(final int deviceIdx) {
		final EditText fld_passphrase = new EditText(TapLockSettings.this);
		// parse the existing passphrase
		JSONObject deviceJObj = mDevices.get(deviceIdx);
		try {
			fld_passphrase.setText(deviceJObj.getString(KEY_PASSPHRASE));
		} catch (JSONException e) {
			Log.e(TAG, e.getMessage());
		}
		mDialog = new AlertDialog.Builder(TapLockSettings.this)
		.setTitle("set passphrase")
		.setView(fld_passphrase)
		.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
				String passphrase = fld_passphrase.getText().toString();
				JSONObject deviceJObj = mDevices.remove(deviceIdx);
				try {
					deviceJObj.put(KEY_PASSPHRASE, passphrase);
				} catch (JSONException e) {
					Log.e(TAG, e.getMessage());
				}
				mDevices.add(deviceIdx, deviceJObj);
				if (mServiceInterface != null) {
					try {
						mServiceInterface.write(deviceJObj.getString(KEY_ADDRESS), ACTION_PASSPHRASE, passphrase);
					} catch (RemoteException e) {
						Log.e(TAG, e.getMessage());
					} catch (JSONException e) {
						Log.e(TAG, e.getMessage());
					}
				} else
					storeDevices();
			}

		})
		.create();
		mDialog.show();
	}

	private void addNewDevice(String name, String address) {
		// duplicate checking
		boolean isDuplicate = false;
		for (JSONObject deviceJObj : mDevices) {
			try {
				if (deviceJObj.getString(KEY_ADDRESS).equals(address)) {
					isDuplicate = true;
					name = deviceJObj.getString(KEY_NAME);
					break;
				}
			} catch (JSONException e) {
				Log.e(TAG, e.getMessage());
			}
		}
		if (isDuplicate)
			Toast.makeText(getApplicationContext(), String.format(getString(R.string.msg_deviceexists), name), Toast.LENGTH_SHORT).show();
		else {
			// new device
			JSONObject deviceJObj = TapLock.createDevice(name, address, DEFAULT_PASSPHRASE);
			mDevices.add(deviceJObj);
			storeDevices();
			// instead of setting the passphrase for new devices, show info
			// setPassphrase(mDevices.size() - 1);
			if (mDevices.size() == 1) {
				// first device added
				Intent i = TapLock.getPackageIntent(TapLockSettings.this, TapLockInfo.class);
				i.putExtra(EXTRA_INFO, getString(R.string.info_newdevice));
				startActivity(i);
			}
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder binder) {
		mServiceInterface = ITapLockService.Stub.asInterface(binder);
		if (mUIInterface != null) {
			try {
				mServiceInterface.setCallback(mUIInterface);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(KEY_DEVICES)) {
			mDevices.clear();
			Set<String> devices = sharedPreferences.getStringSet(KEY_DEVICES, null);
			if (devices != null) {
				for (String device : devices) {
					try {
						mDevices.add(new JSONObject(device));
					} catch (JSONException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
			String[] deviceNames = TapLock.getDeviceNames(mDevices);
			setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, deviceNames));
		}
	}
	
	private void storeDevices() {
		TapLock.storeDevices(this, getSharedPreferences(KEY_PREFS, MODE_PRIVATE), mDevices);
		String[] deviceNames = TapLock.getDeviceNames(mDevices);
		setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, deviceNames));
	}
}
