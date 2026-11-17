package com.Cigram.vid;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;


import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RequestNetworkController {
	public static final String GET = "GET";
	public static final String POST = "POST";
	public static final String PUT = "PUT";
	public static final String DELETE = "DELETE";
	
	public static final int REQUEST_PARAM = 0;
	public static final int REQUEST_BODY = 1;
	
	private static final int SOCKET_TIMEOUT = 15000;
	private static final int READ_TIMEOUT = 25000;
	
	protected OkHttpClient client;
	
	private static RequestNetworkController mInstance;
	
	public static synchronized RequestNetworkController getInstance() {
		if(mInstance == null) {
			mInstance = new RequestNetworkController();
		}
		return mInstance;
	}
	
	private OkHttpClient getClient() {
		if (client == null) {
			OkHttpClient.Builder builder = new OkHttpClient.Builder();
			
			// 18.2 security fix: the generated template installed a trust-all X509TrustManager and a HostnameVerifier that
			// always answered true, so every request through this client (catalogue, account register / login, search)
			// could be read or altered by anyone on the network path. The platform's default certificate and hostname
			// validation is used instead; only the timeouts are configured here.
			builder.connectTimeout(SOCKET_TIMEOUT, TimeUnit.MILLISECONDS);
			builder.readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS);
			builder.writeTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS);
			
			client = builder.build();
		}
		
		return client;
	}
	
	public void execute(final RequestNetwork requestNetwork, String method, String url, final String tag, final RequestNetwork.RequestListener requestListener) {
		Request.Builder reqBuilder = new Request.Builder();
		Headers.Builder headerBuilder = new Headers.Builder();
		
		if (requestNetwork.getHeaders().size() > 0) {
			HashMap<String, Object> headers = requestNetwork.getHeaders();
			
			for (HashMap.Entry<String, Object> header : headers.entrySet()) {
				headerBuilder.add(header.getKey(), String.valueOf(header.getValue()));
			}
		}
		
		try {
			if (requestNetwork.getRequestType() == REQUEST_PARAM) {
				if (method.equals(GET)) {
					HttpUrl.Builder httpBuilder;
					
					try {
						httpBuilder = HttpUrl.parse(url).newBuilder();
					} catch (NullPointerException ne) {
						throw new NullPointerException("unexpected url: " + url);
					}
					
					if (requestNetwork.getParams().size() > 0) {
						HashMap<String, Object> params = requestNetwork.getParams();
						
						for (HashMap.Entry<String, Object> param : params.entrySet()) {
							httpBuilder.addQueryParameter(param.getKey(), String.valueOf(param.getValue()));
						}
					}
					
					reqBuilder.url(httpBuilder.build()).headers(headerBuilder.build()).get();
				} else {
					FormBody.Builder formBuilder = new FormBody.Builder();
					if (requestNetwork.getParams().size() > 0) {
						HashMap<String, Object> params = requestNetwork.getParams();
						
						for (HashMap.Entry<String, Object> param : params.entrySet()) {
							formBuilder.add(param.getKey(), String.valueOf(param.getValue()));
						}
					}
					
					RequestBody reqBody = formBuilder.build();
					
					reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody);
				}
			} else {
				RequestBody reqBody = RequestBody.create(MediaType.parse("application/json"), new Gson().toJson(requestNetwork.getParams()));
				
				if (method.equals(GET)) {
					reqBuilder.url(url).headers(headerBuilder.build()).get();
				} else {
					reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody);
				}
			}
			
			Request req = reqBuilder.build();
			
			getClient().newCall(req).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, final IOException e) {
					// DIAG 1/3: the request never got an answer (DNS, timeout, TLS, offline...)
					android.util.Log.e("CigramDiag", "NETWORK FAIL tag=" + tag + " url=" + call.request().url()
					+ " type=" + e.getClass().getSimpleName() + " msg=" + e.getMessage(), e);
					requestNetwork.getActivity().runOnUiThread(new Runnable() {
						@Override
						public void run() {
							requestListener.onErrorResponse(tag, e.getMessage());
						}
					});
				}
				
				@Override
				public void onResponse(Call call, final Response response) throws IOException {
					final String responseBody = response.body().string().trim();
					// DIAG 2/3: the server answered. Wrong status / HTML page / empty body = server side problem.
					{
						String ct = response.header("Content-Type", "?");
						String head = responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
						if (!response.isSuccessful() || responseBody.isEmpty()
						|| !(responseBody.startsWith("[") || responseBody.startsWith("{"))) {
							android.util.Log.e("CigramDiag", "BAD RESPONSE tag=" + tag + " http=" + response.code()
							+ " type=" + ct + " bytes=" + responseBody.length() + " url=" + call.request().url()
							+ " head=" + head.replace('\n', ' '));
						} else {
							android.util.Log.i("CigramDiag", "OK tag=" + tag + " http=" + response.code()
							+ " type=" + ct + " bytes=" + responseBody.length());
						}
					}
					requestNetwork.getActivity().runOnUiThread(new Runnable() {
						@Override
						public void run() {
							Headers b = response.headers();
							HashMap<String, Object> map = new HashMap<>();
							for (String s : b.names()) {
								map.put(s, b.get(s) != null ? b.get(s) : "null");
							}
							requestListener.onResponse(tag, responseBody, map);
						}
					});
				}
			});
		} catch (Exception e) {
			requestListener.onErrorResponse(tag, e.getMessage());
		}
	}
}
