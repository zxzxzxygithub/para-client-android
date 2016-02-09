/*
 * Copyright 2013-2016 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.client.utils;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.DefaultRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.http.HttpMethodName;
import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends {@code AWS4Signer} implementing the AWS Signature Version 4 algorithm.
 * Also contains a method for signature validation. The signatures that this class produces are
 * compatible with the original AWS SDK implementation.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class Signer extends AWS4Signer {

    private static final Logger logger = LoggerFactory.getLogger(Signer.class);
    private static final SimpleDateFormat timeFormatter =
            new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

    private static final String PARA = "para";

    /**
     * No-args constructor.
     */
    public Signer() {
        super(false);
        super.setServiceName(PARA);
    }

    /**
     * Signs a request using AWS signature V4.
     * @param httpMethod GET/POST/PUT... etc.
     * @param endpoint the hostname of the API server
     * @param resourcePath the path of the resource (starting from root e.g. "/path/to/res")
     * @param headers the headers map
     * @param params the params map
     * @param entity the entity object or null
     * @param accessKey the app's access key
     * @param secretKey the app's secret key
     * @return a signed request. The actual signature is inside the {@code Authorization} header.
     */
    public Map<String, String> sign(int httpMethod, String endpoint, String resourcePath,
                    Map<String, String> headers, Map<String, String> params, InputStream entity,
                    String accessKey, String secretKey) {

        DefaultRequest<?> req = buildAWSRequest(httpMethod, endpoint, resourcePath,
                headers, params, entity);
        sign(req, accessKey, secretKey);
        return req.getHeaders();
    }

    /**
     * Signs a request using AWS signature V4.
     * @param request the request instance
     * @param accessKey the app's access key
     * @param secretKey the app's secret key
     */
    public void sign(DefaultRequest<?> request, String accessKey, String secretKey) {
        super.sign(request, new BasicAWSCredentials(accessKey, secretKey));
        resetDate();
    }

    @Override
    public void setRegionName(String regionName) { }

    private void resetDate() {
        overriddenDate = null;
    }

    private DefaultRequest<?> buildAWSRequest(int httpMethod, String endpoint, String resourcePath,
                       Map<String, String> headers, Map<String, String> params, InputStream entity) {

        DefaultRequest<AmazonWebServiceRequest> r = new DefaultRequest<AmazonWebServiceRequest>(PARA);
        String method;
        switch (httpMethod) {
            case Request.Method.GET: method = "GET"; break;
            case Request.Method.PUT: method = "PUT"; break;
            case Request.Method.POST: method = "POST"; break;
            case Request.Method.PATCH: method = "PATCH"; break;
            case Request.Method.DELETE: method = "DELETE"; break;
            default: method = "GET";
        }
        r.setHttpMethod(HttpMethodName.valueOf(method));
        if (!StringUtils.isBlank(endpoint)) {
            if (!endpoint.startsWith("http")) {
                endpoint = "https://" + endpoint;
            }
            r.setEndpoint(URI.create(endpoint));
        }
        if (!StringUtils.isBlank(resourcePath)) {
            r.setResourcePath(resourcePath);
        }
        if (headers != null) {
            if (headers.containsKey("x-amz-date")) {
                overriddenDate = parseAWSDate(headers.get("x-amz-date"));
            }
            // we don't need these here, added by default
            headers.remove("host");
            headers.remove("x-amz-date");
            r.setHeaders(headers);
        }
        if (params != null) {
            for (Map.Entry<String, String> param : params.entrySet()) {
                r.addParameter(param.getKey(), param.getValue());
            }
        }
        if (entity != null) {
            r.setContent(entity);
        }
        return r;
    }

    /**
     * Returns a parsed Date
     * @param date a date in the AWS format yyyyMMdd'T'HHmmss'Z'
     * @return a date
     */
    public static Date parseAWSDate(String date) {
        if (date == null) {
            return null;
        }
        try {
            return timeFormatter.parse(date);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Builds, signs and executes a request to an API endpoint using the provided credentials.
     * Signs the request using the Amazon Signature 4 algorithm and returns the response.
     * @param accessKey access key
     * @param secretKey secret key
     * @param httpMethod the method (GET, POST...)
     * @param endpointURL protocol://host:port
     * @param reqPath the API resource path relative to the endpointURL
     * @param headers headers map
     * @param params parameters map
     * @param body an object that will be serialized to JSON byte array (payload), could be null
     * @return a response object
     */
    @SuppressWarnings("unchecked")
    public <T> ParaRequest<T> invokeSignedRequest(String accessKey, String secretKey,
            int httpMethod, String endpointURL, String reqPath,
            Map<String, String> headers, Map<String, Object> params, T body, Class<?> type,
            Response.Listener<?> success, Response.ErrorListener error) {

        String url = endpointURL + reqPath;
        String queryString;
        byte[] entity = jsonBytes(body);
        boolean isJWT = StringUtils.startsWithIgnoreCase(secretKey, "Bearer");

        if (type == null) {
            type = (Class<T>) ((body != null) ? body.getClass() : Map.class);
        }

        Map<String, String> signedHeaders = null;
        if (!isJWT) {
            signedHeaders = signRequest(accessKey, secretKey, httpMethod, endpointURL, reqPath,
                    headers, params, entity);
        }

        if (headers == null) {
            headers = new HashMap<String, String>();
        }

        if (params != null) {
            Map<String, List<String>> paramMap = new HashMap<String, List<String>>();
            for (Map.Entry<String, Object> param : params.entrySet()) {
                String key = param.getKey();
                Object value = param.getValue();
                if (value != null) {
                    if (value instanceof List && !((List) value).isEmpty()) {
                        paramMap.put(key, ((List) value));
                    } else {
                        paramMap.put(key, Collections.singletonList(value.toString()));
                    }
                }
            }
            queryString = super.getCanonicalizedQueryString(paramMap);
            if (!queryString.isEmpty()) {
                url += "?" + queryString;
            }
        }

        if (isJWT) {
            headers.put("Authorization", secretKey);
        } else {
            headers.put("Authorization", signedHeaders.get("Authorization"));
            headers.put("X-Amz-Date", signedHeaders.get("X-Amz-Date"));
        }

        return new ParaRequest(httpMethod, url, headers, entity, type, success, error);
    }

    /**
     * Builds and signs a request to an API endpoint using the provided credentials.
     * @param accessKey access key
     * @param secretKey secret key
     * @param httpMethod the method (GET, POST...)
     * @param endpointURL protocol://host:port
     * @param reqPath the API resource path relative to the endpointURL
     * @param headers headers map
     * @param params parameters map
     * @param jsonEntity an object serialized to JSON byte array (payload), could be null
     * @return a map containing the "Authorization" header
     */
    public Map<String, String> signRequest(String accessKey, String secretKey,
               int httpMethod, String endpointURL, String reqPath,
               Map<String, String> headers, Map<String, Object> params, byte[] jsonEntity) {

        if (StringUtils.isBlank(accessKey) || StringUtils.isBlank(secretKey)) {
            logger.warn("Blank access key or secret key for: {} {}", httpMethod, reqPath);
            accessKey = "";
            secretKey = "";
        }

        if (httpMethod < 0 || httpMethod > 7) {
            httpMethod = Request.Method.GET;
        }

        InputStream in = null;
        Map<String, String> sigParams = new HashMap<String, String>();

        if (params != null) {
            for (Map.Entry<String, Object> param : params.entrySet()) {
                String key = param.getKey();
                Object value = param.getValue();
                if (value != null) {
                    if (value instanceof List && !((List) value).isEmpty() &&
                            ((List) value).get(0) != null) {
                        sigParams.put(key, ((List) value).get(0).toString());
                    } else {
                        sigParams.put(key, value.toString());
                    }
                }
            }
        }

        if (jsonEntity != null && jsonEntity.length > 0) {
            // For some reason AWSSDK for Android doesn't like
            // ByteArrayInputStream to be wrapped in BufferedInputStream
            // and throws "IOException: unable to reset stream..."
            in = new ByteArrayInputStream(jsonEntity);
        }

        return sign(httpMethod, endpointURL, reqPath, headers, sigParams, in, accessKey, secretKey);
    }

    private byte[] jsonBytes(Object o) {
        if (o == null) {
            return new byte[0];
        }
        try {
            return ClientUtils.getJsonWriterNoIdent().writeValueAsBytes(o);
        } catch (Exception e) {
            logger.error("Object could not be converted to JSON byte[]", e);
            return new byte[0];
        }
    }

    class ParaRequest<T> extends Request<T> {

        private final Map<String, String> headers;
        private final Response.Listener<T> listener;
        private final Response.ErrorListener errorListener;
        private final byte[] body;
        private final Class<T> type;
private String url;
        /**
         * Make an API request and return a parsed object from JSON.
         * @param url URL of the request to make
         * @param headers Map of request headers
         * @param jsonEntity request body
         * @param entityType the type to return when JSON is deserialized
         * @param successListener success listener
         * @param errorListener error listener
         */
        public ParaRequest(int method, String url, Map<String, String> headers,
                   byte[] jsonEntity, Class<T> entityType,
                   Response.Listener<T> successListener, Response.ErrorListener errorListener) {
            super(method, url, errorListener);
            this.headers = headers;
            this.listener = successListener;
            this.errorListener = errorListener;
            this.body = jsonEntity;
            this.type = entityType;

            this.url = url;
        }

        @Override
        public Map<String, String> getHeaders() throws AuthFailureError {
            return headers != null ? headers : super.getHeaders();
        }

        @Override
        protected void deliverResponse(T response) {
            if (listener != null) {
                listener.onResponse(response);
            }
        }

        @Override
        public void deliverError(VolleyError error) {
            if (errorListener != null) {
                errorListener.onErrorResponse(error);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Response<T> parseNetworkResponse(NetworkResponse response) {
            try {
                if (response != null && response.data != null && response.data.length > 0) {
                    if (ClientUtils.isBasicType(type)) {
                        return (Response<T>) Response.success(new String(response.data, "UTF-8"),
                                HttpHeaderParser.parseCacheHeaders(response));
                    } else {
                        return Response.success((T) ClientUtils.getJsonReader(type).
                                readValue(response.data), HttpHeaderParser.parseCacheHeaders(response));
                    }
                } else {
                    return Response.success(null, HttpHeaderParser.parseCacheHeaders(response));
                }
            } catch (Exception e) {
                logger.error("JSON parsing error", e);
                return Response.error(new ParseError(e));
            }
        }

        @Override
        public String getBodyContentType() {
            return "application/json; charset=utf-8";
        }

        @Override
        public byte[] getBody() {
            return body;
        }
    }

}