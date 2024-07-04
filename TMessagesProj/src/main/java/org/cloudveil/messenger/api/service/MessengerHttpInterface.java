package org.cloudveil.messenger.api.service;


import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.api.model.response.SettingsResponse;

import io.reactivex.Observable;
import okhttp3.ResponseBody;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface MessengerHttpInterface {
    public static final String PING_SUCCCESS = "Success";
    @POST("settings")
    Observable<SettingsResponse> loadSettings(@Body SettingsRequest request);
    @GET("ping")
    Observable<ResponseBody> ping();
}