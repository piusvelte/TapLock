package com.piusvelte.remoteauthclient;

interface IRemoteAuthClientUI {
	void setMessage(String message);
	void setUnpairedDevice(String device);
	void setDiscoveryFinished();
}