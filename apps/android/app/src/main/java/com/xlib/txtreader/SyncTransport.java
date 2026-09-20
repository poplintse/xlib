package com.xlib.txtreader;

import java.util.List;

/** Blocking HTTP port. All business requests execute on the same serial queue. */
interface SyncTransport {
    boolean configured();
    void setBaseUrl(String url);
    SyncApiClient.StartSyncResponse startSync(String email, String deviceId, String name, String version) throws Exception;
    List<RemoteProgressSnapshot> pullProgress(String token, String deviceId) throws Exception;
    RemoteProgressSnapshot syncProgress(String token, String deviceId, LocalProgressSnapshot snapshot) throws Exception;
    List<SyncDevice> listDevices(String token, String deviceId) throws Exception;
    void revokeDevice(String token, String deviceId, String target) throws Exception;
    void deleteBookProgress(String token, String deviceId, BookKey book) throws Exception;
    void health() throws Exception;
}
