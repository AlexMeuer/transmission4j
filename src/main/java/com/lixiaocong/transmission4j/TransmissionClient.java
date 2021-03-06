/**
 * Copyright (c) 2016, lixiaocong <lxccs@iCloud.com>
 * All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * <p>
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * <p>
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * <p>
 * Neither the name of transmission4j nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.lixiaocong.transmission4j;

import com.lixiaocong.transmission4j.exception.AuthException;
import com.lixiaocong.transmission4j.exception.JsonException;
import com.lixiaocong.transmission4j.exception.NetworkException;
import com.lixiaocong.transmission4j.request.*;
import com.lixiaocong.transmission4j.response.SessionStatsResponse;
import com.lixiaocong.transmission4j.response.Torrent;
import com.lixiaocong.transmission4j.response.TorrentGetResponse;
import com.lixiaocong.transmission4j.response.TransmissionResponse;
import com.lixiaocong.transmission4j.utils.JsonUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Transmission java client
 */
public class TransmissionClient {
    private static Log log = LogFactory.getLog(TransmissionClient.class.getName());

    private String username;
    private String password;
    private String id;  //id is used in transmission rpc, details in https://trac.transmissionbt.com/browser/trunk/extras/rpc-spec.txt

    private HttpClient httpClient;
    private HttpPost httpPost;

    public TransmissionClient(String username, String password, String uri) {
        log.info("new TransmissionClient username:" + username + " password:" + password + " uri:" + uri);
        this.username = username;
        this.password = password;
        this.id = null;

        buildHttpClient();

        httpPost = new HttpPost(uri);
        RequestConfig config = RequestConfig.custom().setSocketTimeout(2000).setConnectTimeout(2000).setConnectionRequestTimeout(2000).build();
        httpPost.setConfig(config);
    }

    private void buildHttpClient() {
        log.info("build client with X-Transmission-Session-Id:" + id);
        Header authHeader = new BasicHeader(HttpHeaders.AUTHORIZATION, String.format("Basic %s", Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8))));
        Header idHeader = new BasicHeader("X-Transmission-Session-Id", id);
        List<Header> headers = new ArrayList<>();
        headers.add(authHeader);
        headers.add(idHeader);
        httpClient = HttpClients.custom().setDefaultHeaders(headers).build();
    }

    /**
     * @param request
     * @param responseClass
     * @param <T>
     * @return
     * @throws NetworkException when there is an error connecting to transmission server
     * @throws AuthException    username or password in correct
     */
    private <T extends TransmissionResponse> T execute(TransmissionRequest request, Class<T> responseClass) throws NetworkException, AuthException {
        String requestStr;
        try {
            requestStr = JsonUtil.getJson(request);
        } catch (JsonException e) {
            log.error(e);
            throw new RuntimeException(e.getMessage());
        }

        log.info("execute request " + requestStr);
        httpPost.setEntity(new StringEntity(requestStr, ContentType.APPLICATION_JSON));
        HttpResponse response;
        try {
            response = httpClient.execute(httpPost);
        } catch (IOException e) {
            log.error(e);
            throw new NetworkException(e.getMessage());
        }

        int code = response.getStatusLine().getStatusCode();
        if (code == HttpStatus.SC_OK) {
            String responseStr;
            try {
                responseStr = EntityUtils.toString(response.getEntity());
            } catch (IOException e) {
                log.warn("read content of " + requestStr + ". exception:", e);
                throw new NetworkException(e.getMessage());
            }
            log.info("execute response " + responseStr);
            try {
                return JsonUtil.getObject(responseClass, responseStr);
            } catch (JsonException e) {
                log.error(e);
                throw new RuntimeException(e.getMessage());
            }
        } else if (code == HttpStatus.SC_CONFLICT) {
            log.info("execute response 409");
            Header[] headers = response.getHeaders("X-Transmission-Session-Id");
            if (headers.length == 0)
                throw new RuntimeException("transmission return 409 without id");
            id = headers[0].getValue();
            buildHttpClient();
            return execute(request, responseClass);
        } else if (code == HttpStatus.SC_UNAUTHORIZED) {
            log.info("execute response 401");
            throw new AuthException("username: " + username + " or password " + password + " incorrect");
        }
        log.error("execute error with response code " + code);
        throw new NetworkException("execute error with response code " + code);
    }

    public boolean startAll() throws AuthException, NetworkException {
        TorrentStartRequest startAllReuqest = TransmissionRequestFactory.getStartAllReuqest();
        TransmissionResponse response = execute(startAllReuqest, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean start(List<Integer> ids) throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getStartRequest(ids);
        TransmissionResponse response = execute(request, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean stopAll() throws AuthException, NetworkException {
        TorrentStopRequest stopAllRequest = TransmissionRequestFactory.getStopAllRequest();
        TransmissionResponse response = execute(stopAllRequest, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean stop(List<Integer> ids) throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getStopRequest(ids);
        TransmissionResponse response = execute(request, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean add(String metainfo) throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getAddRequest(metainfo);
        TransmissionResponse response = execute(request, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean removeAll() throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getRemoveAllRequest();
        TransmissionResponse response = execute(request, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public boolean remove(List<Integer> ids) throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getRemoveRequest(ids);
        TransmissionResponse response = execute(request, TransmissionResponse.class);
        return response.getResult().equals("success");
    }

    public List<Torrent> getAll() throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getgetAllRequest();
        TorrentGetResponse response = execute(request, TorrentGetResponse.class);
        return response.getArguments().getTorrents();
    }

    public List<Torrent> get(List<Integer> ids) throws AuthException, NetworkException {
        TransmissionRequest request = TransmissionRequestFactory.getGetRequest(ids);
        TorrentGetResponse response = execute(request, TorrentGetResponse.class);
        return response.getArguments().getTorrents();
    }

    public SessionStatsResponse sessionStats() throws AuthException, NetworkException {
        TransmissionRequest request = new SessionStatsRequest();
        return execute(request, SessionStatsResponse.class);
    }
}